package com.syx.demo.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.syx.demo.entity.MedicalKnowledge;
import com.syx.demo.mapper.MedicalKnowledgeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库管理服务：负责 medical_knowledge 表的增删改查，
 * 并在数据变更后自动触发向量库重建，保证 MySQL 与 Redis 向量一致。
 */
@Service
public class KnowledgeService {

    private final MedicalKnowledgeMapper knowledgeMapper;
    private final KnowledgeVectorService knowledgeVectorService;

    public KnowledgeService(MedicalKnowledgeMapper knowledgeMapper,
                            KnowledgeVectorService knowledgeVectorService) {
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeVectorService = knowledgeVectorService;
    }

    /** 查询全部知识（按 id 倒序，最新的在前） */
    public List<MedicalKnowledge> listAll() {
        return knowledgeMapper.selectList(
                Wrappers.<MedicalKnowledge>lambdaQuery().orderByDesc(MedicalKnowledge::getId));
    }

    /** 按分类查询 */
    public List<MedicalKnowledge> listByCategory(String category) {
        return knowledgeMapper.selectList(
                Wrappers.<MedicalKnowledge>lambdaQuery()
                        .eq(MedicalKnowledge::getCategory, category)
                        .orderByDesc(MedicalKnowledge::getId));
    }

    /** 查询单条 */
    public MedicalKnowledge getById(Long id) {
        return knowledgeMapper.selectById(id);
    }

    /** 新增知识，并自动重建向量库 */
    @Transactional
    public MedicalKnowledge add(MedicalKnowledge knowledge) {
        if (knowledge.getStatus() == null) {
            knowledge.setStatus(1);
        }
        if (knowledge.getCreatedAt() == null) {
            knowledge.setCreatedAt(LocalDateTime.now());
        }
        knowledgeMapper.insert(knowledge);
        // 数据落库后重建向量库，新增知识立即可以检索到
        knowledgeVectorService.rebuildVectorStore();
        return knowledge;
    }

    /** 修改知识，并自动重建向量库 */
    @Transactional
    public MedicalKnowledge update(MedicalKnowledge knowledge) {
        MedicalKnowledge exist = knowledgeMapper.selectById(knowledge.getId());
        if (exist == null) {
            throw new IllegalArgumentException("知识不存在，id=" + knowledge.getId());
        }
        knowledgeMapper.updateById(knowledge);
        knowledgeVectorService.rebuildVectorStore();
        return knowledgeMapper.selectById(knowledge.getId());
    }

    /** 删除知识，并自动重建向量库 */
    @Transactional
    public boolean delete(Long id) {
        int rows = knowledgeMapper.deleteById(id);
        if (rows > 0) {
            knowledgeVectorService.rebuildVectorStore();
        }
        return rows > 0;
    }

    /** 统计总数 */
    public long count() {
        return knowledgeMapper.selectCount(null);
    }
}
