package com.syx.demo.controller;

import com.syx.demo.entity.MedicalKnowledge;
import com.syx.demo.service.KnowledgeService;
import com.syx.demo.service.KnowledgeVectorService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口：供前端 Vue 项目调用。
 * 增删改会自动重建向量库，无需手动调 rebuild。
 *
 * <p><b>【在链路里的位置】</b>它不在问答链路上，而是"知识入库线"的入口：
 * <pre>
 *   本类 ─┬─ KnowledgeService        MySQL 增删改查（原文的唯一真相）
 *         └─ KnowledgeVectorService  切分 → embedding → 写 Redis（检索用的副本）
 * </pre>
 *
 * <p>关键认知：<b>向量库不是数据库的从库，它不会自动同步</b>。
 * 直接改 MySQL（比如跑 SQL 脚本）之后必须调一次 {@code /rebuild}，
 * 否则问答用的还是旧的向量，表现为"我明明改了资料，回答却没变"。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeVectorService knowledgeVectorService;
    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeVectorService knowledgeVectorService,
                               KnowledgeService knowledgeService) {
        this.knowledgeVectorService = knowledgeVectorService;
        this.knowledgeService = knowledgeService;
    }

    /**
     * 强制重建向量库：清空 Redis 旧向量后，从 MySQL 全量重新构建。
     * 新增/修改 medical_knowledge 表数据后调用本接口即可生效，无需重启。
     */
    @PostMapping("/rebuild")
    public String rebuild() {
        knowledgeVectorService.rebuildVectorStore();
        return "向量库重建完成";
    }

    /** 知识列表（可按分类筛选）：GET /api/knowledge/list?category=急救指南 */
    @GetMapping("/list")
    public List<MedicalKnowledge> list(@RequestParam(required = false) String category) {
        if (category == null || category.isBlank() || "全部".equals(category)) {
            return knowledgeService.listAll();
        }
        return knowledgeService.listByCategory(category);
    }

    /** 知识详情 */
    @GetMapping("/{id}")
    public MedicalKnowledge detail(@PathVariable Long id) {
        return knowledgeService.getById(id);
    }

    /** 新增知识（自动重建向量库） */
    @PostMapping("/add")
    public MedicalKnowledge add(@RequestBody MedicalKnowledge knowledge) {
        return knowledgeService.add(knowledge);
    }

    /** 修改知识（自动重建向量库） */
    @PutMapping
    public MedicalKnowledge update(@RequestBody MedicalKnowledge knowledge) {
        return knowledgeService.update(knowledge);
    }

    /** 删除知识（自动重建向量库） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        boolean ok = knowledgeService.delete(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        result.put("message", ok ? "删除成功，向量库已重建" : "记录不存在，id=" + id);
        return result;
    }

    /** 统计信息：知识条数（供前端首页展示） */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", knowledgeService.count());
        return result;
    }
}
