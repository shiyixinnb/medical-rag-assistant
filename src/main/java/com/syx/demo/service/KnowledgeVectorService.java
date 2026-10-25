package com.syx.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.syx.demo.entity.MedicalKnowledge;
import com.syx.demo.entity.PatientKnowledge;
import com.syx.demo.llm.domain.KnowledgeDomain;
import com.syx.demo.llm.domain.VectorStoreConfiguration;
import com.syx.demo.mapper.MedicalKnowledgeMapper;
import com.syx.demo.mapper.PatientKnowledgeMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量库构建服务 —— 把 MySQL 里的知识同步到两个物理隔离的向量索引里。
 *
 * <p>与改造前的区别：原来是"一张表 + 一个索引"，现在是
 * <b>"公共知识表 → 公共索引"、"患者知识表 → 患者索引"</b>，每条 Document 都带上
 * {@code domain}（以及患者域的 {@code tenantId} / {@code patientId}）元数据。
 *
 * <p>元数据不是可选的装饰：{@code VectorStoreConfiguration} 里把这些字段声明成了 RediSearch 的
 * TAG，检索侧的过滤表达式和 Java 侧复核都依赖它。漏写元数据的文档会被检索层直接丢弃 ——
 * 这是刻意的，<b>宁可查不到，也不能让一条没有域标记的文档混进回答里</b>。
 */
@Service
public class KnowledgeVectorService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorService.class);

    private final MedicalKnowledgeMapper medicalMapper;
    private final PatientKnowledgeMapper patientMapper;
    private final VectorStore publicVectorStore;
    private final VectorStore patientVectorStore;

    public KnowledgeVectorService(MedicalKnowledgeMapper medicalMapper,
                                  PatientKnowledgeMapper patientMapper,
                                  @Qualifier(VectorStoreConfiguration.BEAN_PUBLIC) VectorStore publicVectorStore,
                                  @Qualifier(VectorStoreConfiguration.BEAN_PATIENT) VectorStore patientVectorStore) {
        this.medicalMapper = medicalMapper;
        this.patientMapper = patientMapper;
        this.publicVectorStore = publicVectorStore;
        this.patientVectorStore = patientVectorStore;
    }

    /**
     * 启动时自动加载（幂等）。
     *
     * <p>两个域分别判断：公共域有数据就跳过、没有就建；患者域同理。
     * 原来是"全局一个有数据就全跳过"，患者表后加进来时会一直建不起来。
     */
    @PostConstruct
    public void buildKnowledgeVector() {
        log.info("========== 知识向量库构建检查（公共域 / 患者域）==========");
        buildIfEmpty(KnowledgeDomain.PUBLIC, publicVectorStore);
        buildIfEmpty(KnowledgeDomain.PATIENT, patientVectorStore);
        log.info("======================================================");
    }

    /** 强制重建：清空两个域的旧向量后全量重建（供 REST 接口调用）。 */
    public void rebuildVectorStore() {
        log.info("========== 强制重建知识向量库 ==========");
        clearByPrefix(KnowledgeDomain.PUBLIC, VectorStoreConfiguration.PUBLIC_PREFIX);
        clearByPrefix(KnowledgeDomain.PATIENT, VectorStoreConfiguration.PATIENT_PREFIX);
        buildDomain(KnowledgeDomain.PUBLIC, publicVectorStore);
        buildDomain(KnowledgeDomain.PATIENT, patientVectorStore);
        log.info("=======================================");
    }

    // ------------------------------------------------------------------ 内部实现

    private void buildIfEmpty(KnowledgeDomain domain, VectorStore store) {
        try {
            List<Document> existing = store.similaritySearch(
                    SearchRequest.builder().query("check").topK(1).build());
            if (!existing.isEmpty()) {
                log.info("⏭️ {} 向量库已有数据，跳过构建（新增知识后请调用 POST /api/knowledge/rebuild）",
                        domain.getLabel());
                return;
            }
        }
        catch (Exception ex) {
            // 查询失败可能是索引刚建、还没数据，继续往下走尝试构建
            log.debug("{} 向量库探查失败，继续尝试构建：{}", domain.getLabel(), ex.getMessage());
        }
        buildDomain(domain, store);
    }

    private void buildDomain(KnowledgeDomain domain, VectorStore store) {
        List<Document> documents;
        try {
            documents = domain == KnowledgeDomain.PUBLIC
                    ? loadPublicDocuments() : loadPatientDocuments();
        }
        catch (DataAccessException ex) {
            // 表还没建 / 库连不上，不能因为这个把整个应用拖死 ——
            // 但必须打成 ERROR 并给出修复动作，否则就会变成患者域静默无数据这种最难查的问题
            log.error("❌ {} 读取失败，该数据域本轮不会入库：{}", domain.getLabel(), ex.getMessage());
            log.error("   若是首次部署，请先执行建表脚本：src/main/resources/db/patient_knowledge.sql");
            return;
        }
        if (documents.isEmpty()) {
            log.info("⚠️ {} 暂无启用数据，跳过", domain.getLabel());
            return;
        }
        TextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(documents);
        store.add(chunks);
        log.info("✅ {} 构建完成：原始 {} 条 → 分块 {} 条", domain.getLabel(), documents.size(), chunks.size());
    }

    /** 公共知识域：指南 / 药品说明 / 诊疗规范，非患者隐私数据。 */
    private List<Document> loadPublicDocuments() {
        List<MedicalKnowledge> list = medicalMapper.selectList(
                new LambdaQueryWrapper<MedicalKnowledge>().eq(MedicalKnowledge::getStatus, 1));
        List<Document> documents = new ArrayList<>(list.size());
        for (MedicalKnowledge k : list) {
            Map<String, Object> meta = new java.util.HashMap<>();
            put(meta, VectorStoreConfiguration.META_DOMAIN, KnowledgeDomain.PUBLIC.getKey());
            put(meta, "id", k.getId());
            put(meta, "title", k.getTitle());
            put(meta, VectorStoreConfiguration.META_CATEGORY, k.getCategory());
            put(meta, "source", k.getSource());
            put(meta, "tags", k.getTags());
            documents.add(new Document(k.getContent(), meta));
        }
        log.info("📖 公共知识域：从 MySQL 读取到 {} 条", documents.size());
        return documents;
    }

    /**
     * 患者隐私数据域：本院患者资料。
     *
     * <p>每条都必须带上 {@code tenantId}，否则这条文档在检索侧会被当作越域数据丢弃 ——
     * 没有租户归属的患者数据是不可控的，宁可不用。
     */
    private List<Document> loadPatientDocuments() {
        List<PatientKnowledge> list = patientMapper.selectList(
                new LambdaQueryWrapper<PatientKnowledge>().eq(PatientKnowledge::getStatus, 1));
        List<Document> documents = new ArrayList<>(list.size());
        int missingTenant = 0;
        for (PatientKnowledge k : list) {
            if (k.getTenantId() == null || k.getTenantId().isBlank()) {
                missingTenant++;
                continue;
            }
            Map<String, Object> meta = new java.util.HashMap<>();
            put(meta, VectorStoreConfiguration.META_DOMAIN, KnowledgeDomain.PATIENT.getKey());
            put(meta, VectorStoreConfiguration.META_TENANT, k.getTenantId());
            put(meta, VectorStoreConfiguration.META_PATIENT, k.getPatientId());
            put(meta, "id", k.getId());
            put(meta, "title", k.getTitle());
            put(meta, VectorStoreConfiguration.META_CATEGORY, k.getCategory());
            put(meta, "source", k.getSource());
            documents.add(new Document(k.getContent(), meta));
        }
        if (missingTenant > 0) {
            log.warn("⚠️ 患者知识域有 {} 条记录缺少 tenantId，已跳过（无租户归属的患者数据不允许入库）", missingTenant);
        }
        log.info("📖 患者隐私域：从 MySQL 读取到 {} 条可用", documents.size());
        return documents;
    }

    /**
     * 写入元数据的统一入口：跳过 null 值。
     *
     * <p>M7 的 {@code Document} 构造器内部是 {@code Assert.noNullElements} ——
     * metadata 里只要有任何一个 null 值就直接抛 IllegalArgumentException。
     * 而 MySQL 里的 tags/source/category 完全可能是 NULL（很常见），
     * 所以必须在这里过滤掉，否则一条脏数据就能让整个应用起不来。
     */
    private void put(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, value);
        }
    }

    /** 按 key 前缀清空某个域的向量（保留 RediSearch 索引定义，无需重建索引）。 */
    private void clearByPrefix(KnowledgeDomain domain, String prefix) {
        VectorStore store = domain == KnowledgeDomain.PATIENT ? patientVectorStore : publicVectorStore;
        store.getNativeClient().ifPresent(client -> {
            if (client instanceof JedisPooled jedis) {
                long count = 0;
                for (String key : jedis.keys(prefix + "*")) {
                    jedis.del(key);
                    count++;
                }
                log.info("🧹 已清除 {} 旧向量 {} 条（前缀 {}）", domain.getLabel(), count, prefix);
            }
        });
    }
}
