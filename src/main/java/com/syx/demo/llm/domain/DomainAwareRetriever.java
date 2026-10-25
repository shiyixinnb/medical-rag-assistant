package com.syx.demo.llm.domain;

import com.syx.demo.llm.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据域感知的检索器 —— <b>fail-closed</b> 的实现者。
 *
 * <p>"fail-closed"（失败即封闭）在这里的具体含义：只要授权信息不完整、缺失、或校验不过，
 * 就<b>当作没有权限</b>处理，而不是"当作没限制"。反过来的 fail-open 写法是
 * "授权参数为空 → 跳过过滤"，那正是数据泄漏事故的经典成因。
 *
 * <p>检索过程分三层，逐层收紧：
 *
 * <ol>
 *   <li><b>物理隔离</b>：未授权的数据域，对应的 VectorStore 根本不会被调用（最外层，最可靠）；</li>
 *   <li><b>索引过滤</b>：患者域内部再用 {@code domain == 'PATIENT' && tenantId == 'xxx'} 约束。
 *       注意 Spring AI M7 的 Redis 过滤器转换器<b>不转义</b> TAG 值，见
 *       {@link #escapeTagValue(String)} —— 少了这一步，院区 ID 一带连字符过滤就整条失效；</li>
 *   <li><b>Java 复核</b>：对返回的每条 Document 再核对一次 domain 与 tenantId，
 *       不匹配就丢弃并计数。这一层是"索引结构被改坏了 / 过滤表达式没生效"的兜底 ——
 *       正常情况下它应该永远丢弃 0 条，一旦丢弃就需要立刻查原因。</li>
 * </ol>
 *
 * <p><b>【阅读导航】</b>
 * <ul>
 *   <li><b>在链路里的位置</b>：一次问答的第 ⑤ 步（{@code prepare()} 里调 {@code retrieve}）。</li>
 *   <li><b>上游</b>：{@code ClinicalAssistantService.prepare}，带着"问题 + 要查哪些域 + tenantId"进来。</li>
 *   <li><b>下游</b>：Redis 里的两个索引（{@code emergency_public_index} / {@code emergency_patient_index}）。</li>
 *   <li><b>建议读法</b>：先看 {@link #retrieve} 的四个阶段（筛域 → 检索 → 复核 → 重排），
 *       再单独看 {@link #escapeTagValue} —— 那 18 行是"踩过坑"的浓缩。</li>
 * </ul>
 */
@Component
public class DomainAwareRetriever {

    private static final Logger log = LoggerFactory.getLogger(DomainAwareRetriever.class);

    private final VectorStore publicStore;
    private final VectorStore patientStore;
    private final LlmProperties properties;

    /** 索引缺少元数据字段时只警告一次，避免每条请求都刷屏。 */
    private final AtomicBoolean filterWarningLogged = new AtomicBoolean(false);

    public DomainAwareRetriever(
            @Qualifier(VectorStoreConfiguration.BEAN_PUBLIC) VectorStore publicStore,
            @Qualifier(VectorStoreConfiguration.BEAN_PATIENT) VectorStore patientStore,
            LlmProperties properties) {
        this.publicStore = publicStore;
        this.patientStore = patientStore;
        this.properties = properties;
    }

    /**
     * 按授权数据域检索。
     *
     * @param question  用户问题
     * @param requested 本次请求声明的数据域（由意图 + 会话授权共同决定）
     * @param tenantId  机构标识，访问患者域时必填，为空则视为未授权
     */
    public RetrievalResult retrieve(String question, Set<KnowledgeDomain> requested, String tenantId) {
        return retrieve(question, requested, tenantId, null);
    }

    /**
     * 带患者维度收敛的检索。
     *
     * @param patientId 问题里显式点名的患者标识；非空时患者域会再加一层
     *                  {@code patientId == 'xxx'} 过滤 —— 这是"拿 A 机构的授权去问 B 机构患者"
     *                  时能确定性查空（进而拒答）而不是靠模型自行判断的关键。
     */
    public RetrievalResult retrieve(String question, Set<KnowledgeDomain> requested, String tenantId,
                                    String patientId) {
        String scopePatientId = isBlank(patientId) ? null : patientId.trim();
        Set<KnowledgeDomain> allowed = new LinkedHashSet<>();
        Set<KnowledgeDomain> denied = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (KnowledgeDomain domain : requested) {
            // fail-closed：患者域必须有明确租户才允许访问
            if (domain == KnowledgeDomain.PATIENT && isBlank(tenantId)) {
                denied.add(domain);
                notes.add("患者隐私数据域需要携带 tenantId 才能访问，本次未提供，已按未授权处理（fail-closed）");
                continue;
            }
            allowed.add(domain);
        }

        if (allowed.isEmpty()) {
            notes.add("未获得任何数据域的访问授权，本轮不做检索");
            log.warn("[Domain] 无可用数据域，tenantId={}，请求域={}", tenantId, requested);
            return new RetrievalResult(new ArrayList<>(), new LinkedHashSet<>(), denied, notes, 0);
        }

        LlmProperties.Rag rag = properties.getRag();
        List<RetrievedSnippet> collected = new ArrayList<>();
        int violations = 0;
        int scopeMisses = 0;

        for (KnowledgeDomain domain : allowed) {
            VectorStore store = domain == KnowledgeDomain.PATIENT ? patientStore : publicStore;
            // 多取一些，给 Java 侧复核丢掉越域片段留余量
            int fetch = Math.max(rag.getTopK(), rag.getTopK() * 2);
            List<Document> docs = search(store, question, domain, tenantId, scopePatientId,
                    fetch, rag.getSimilarityThreshold());

            for (Document doc : docs) {
                KnowledgeDomain docDomain = readDomain(doc);
                if (docDomain != domain) {
                    // 走到这里说明隔离层出了问题，绝不能把这条片段交给模型
                    violations++;
                    log.error("[Domain] ⚠️ 越域片段已被丢弃！期望域={} 实际域={} docId={} —— 请检查向量库索引的元数据字段配置",
                            domain, docDomain, doc.getId());
                    continue;
                }
                if (domain == KnowledgeDomain.PATIENT) {
                    String docTenant = str(doc.getMetadata().get(VectorStoreConfiguration.META_TENANT));
                    if (!tenantId.equals(docTenant)) {
                        violations++;
                        log.error("[Domain] ⚠️ 跨租户片段已被丢弃！期望租户={} 片段租户={} docId={}",
                                tenantId, docTenant, doc.getId());
                        continue;
                    }
                    if (scopePatientId != null) {
                        String docPatient = str(doc.getMetadata().get(VectorStoreConfiguration.META_PATIENT));
                        if (!scopePatientId.equals(docPatient)) {
                            // 不是安全问题（仍在授权机构内），是"患者维度收敛没生效"，
                            // 通常意味着索引过滤又退化了，所以单独计数、单独提示
                            scopeMisses++;
                            log.warn("[Domain] 患者维度收敛未生效，已丢弃：期望患者={} 片段患者={} docId={}",
                                    scopePatientId, docPatient, doc.getId());
                            continue;
                        }
                    }
                }
                collected.add(toSnippet(doc, domain, collected.size() + 1));
            }
        }

        if (collected.isEmpty()) {
            notes.add("在已授权的数据域内未检索到相关资料");
        }

        // 合并后按相关度重排，并重新编号，保证 [1] 就是最相关的那条
        collected.sort(Comparator.comparing((RetrievedSnippet s) -> s.getScore() == null ? 0d : s.getScore())
                .reversed());
        List<RetrievedSnippet> ordered = new ArrayList<>(collected.size());
        for (int i = 0; i < collected.size(); i++) {
            RetrievedSnippet s = collected.get(i);
            ordered.add(new RetrievedSnippet(i + 1, s.getDomain(), s.getDocId(), s.getTitle(), s.getSource(),
                    s.getContent(), s.getScore(), s.getTenantId(), s.getPatientId()));
        }
        if (ordered.size() > rag.getTopK()) {
            ordered = new ArrayList<>(ordered.subList(0, rag.getTopK()));
        }

        if (violations > 0) {
            notes.add("本轮丢弃了 " + violations + " 条越域片段（属异常情况，已记录 ERROR 日志）");
        }
        if (scopeMisses > 0) {
            notes.add("本轮丢弃了 " + scopeMisses + " 条非目标患者的片段（患者维度收敛未在索引层生效，"
                    + "已在 Java 侧兜住，请检查索引过滤）");
        }
        if (scopePatientId != null) {
            notes.add("检索已按问题中点名的患者标识收敛：patientId=" + scopePatientId);
        }
        return new RetrievalResult(ordered, allowed, denied, notes, violations);
    }

    /**
     * 在指定数据域内检索。
     *
     * <p>先带过滤表达式查；如果索引里没有对应的元数据字段（RediSearch 会报未知字段），
     * 退化为无过滤查询，交由上层的 Java 复核来兜底。这样"索引没建好"不会导致功能不可用，
     * 但也不会牺牲安全性。
     */
    private List<Document> search(VectorStore store, String question, KnowledgeDomain domain,
                                  String tenantId, String patientId, int topK, double threshold) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(buildFilter(domain, tenantId, patientId))
                    .build();
            return store.similaritySearch(request);
        }
        catch (RuntimeException ex) {
            if (filterWarningLogged.compareAndSet(false, true)) {
                log.warn("[Domain] 索引过滤不可用（{}），已退化为「检索后 Java 复核」模式，隔离仍然成立但精度下降。"
                                + "先确认索引是否声明了 domain/tenantId 的 TAG 字段，再确认标识里有没有未被转义的标点（如 HOSP-A 的连字符）：{}",
                        domain.getLabel(), ex.getMessage());
            }
            try {
                return store.similaritySearch(SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .build());
            }
            catch (RuntimeException inner) {
                log.warn("[Domain] 检索失败，按空结果继续（{}）：{}", domain.getLabel(), inner.getMessage());
                return List.of();
            }
        }
    }

    /** 构造 RediSearch 过滤表达式。 */
    private Filter.Expression buildFilter(KnowledgeDomain domain, String tenantId, String patientId) {
        String domainField = properties.getDomain().getDomainField();
        Filter.Expression expr = tagEquals(domainField, domain.getKey());
        if (domain != KnowledgeDomain.PATIENT) {
            return expr;
        }
        expr = new Filter.Expression(Filter.ExpressionType.AND, expr,
                tagEquals(properties.getDomain().getTenantField(), tenantId));
        if (patientId != null) {
            expr = new Filter.Expression(Filter.ExpressionType.AND, expr,
                    tagEquals(properties.getDomain().getPatientField(), patientId));
        }
        return expr;
    }

    /** 构造一个 TAG 等值条件，值会先做 RediSearch 转义。 */
    private Filter.Expression tagEquals(String field, String rawValue) {
        return new Filter.Expression(Filter.ExpressionType.EQ,
                new Filter.Key(field), new Filter.Value(escapeTagValue(rawValue)));
    }

    /**
     * 转义 TAG 值里的 RediSearch 特殊字符。**这一步不做，索引过滤会整条失效。**
     *
     * <p>实测（M7 + RediSearch）：Spring AI 的 {@code RedisFilterExpressionConverter} 把
     * {@code tenantId == 'HOSP-A'} 直接拼成 {@code @tenantId:{HOSP-A}}，<b>不做任何转义</b>；
     * 而 RediSearch 的查询解析器把 {@code - . : , ( ) 空格} 等当成标点分隔符，
     * 于是 {@code {HOSP-A}} 被切成 {@code HOSP} + {@code -A}，整条表达式报
     * {@code Syntax error at offset N near A}。
     *
     * <p>后果不是"报个错就完了"：过滤失败后本类会退化成「检索后 Java 复核」模式，
     * 隔离仍然成立（fail-closed），但每一轮都要先捞回别的院区的片段再丢掉，
     * 既浪费 topK 名额也污染日志。所以院区 ID 只要带连字符（{@code HOSP-A}）、
     * 患者 ID 带连字符（{@code P-1001}），就必然踩到。
     *
     * <p>转义策略：ASCII 范围内非 {@code [A-Za-z0-9_]} 的字符一律加反斜杠；
     * 非 ASCII（中文院区名等）保持原样，避免把 {@code \院} 这种可疑写法喂给解析器。
     */
    static String escapeTagValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean plainAscii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (c > 127 || plainAscii) {
                sb.append(c);
            }
            else {
                sb.append('\\').append(c);
            }
        }
        return sb.toString();
    }

    private KnowledgeDomain readDomain(Document doc) {
        String key = str(doc.getMetadata().get(VectorStoreConfiguration.META_DOMAIN));
        return KnowledgeDomain.fromKey(key);
    }

    private RetrievedSnippet toSnippet(Document doc, KnowledgeDomain domain, int index) {
        Object title = doc.getMetadata().get(VectorStoreConfiguration.META_TITLE);
        Object source = doc.getMetadata().get(VectorStoreConfiguration.META_SOURCE);
        return new RetrievedSnippet(index, domain, doc.getId(),
                title == null ? null : title.toString(),
                source == null ? null : source.toString(),
                doc.getText(), doc.getScore(),
                str(doc.getMetadata().get(VectorStoreConfiguration.META_TENANT)),
                str(doc.getMetadata().get(VectorStoreConfiguration.META_PATIENT)));
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 便于测试/诊断：某数据域当前是否可访问。 */
    public boolean isAccessible(KnowledgeDomain domain, String tenantId) {
        return domain == KnowledgeDomain.PUBLIC || !isBlank(tenantId);
    }
}
