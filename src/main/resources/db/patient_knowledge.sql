-- ============================================================================
--  患者隐私知识域 —— 建表脚本
--
--  设计要点：患者隐私数据单独一张表，与 medical_knowledge（公共知识域）物理分开。
--  这样"DBA 只给某院授权 patient_knowledge"成为可能的运维动作，
--  也让任何一句 select * from medical_knowledge 都不可能带出患者隐私。
--
--  tenant_id 是强制维度：缺 tenant_id 的记录不会进入向量库
--  （KnowledgeVectorService.loadPatientDocuments 会跳过并打警告），
--  因为无租户归属的患者数据在检索侧无法判定归属，属于不可控数据。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `patient_knowledge`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`  VARCHAR(64)  NOT NULL COMMENT '机构/院区标识，多租户隔离的关键维度',
    `patient_id` VARCHAR(64)  DEFAULT NULL COMMENT '患者业务标识（建议密码学脱敏后的就诊号/病案号，禁止存身份证号、手机号）',
    `title`      VARCHAR(255) DEFAULT NULL COMMENT '标题，例如"2026-08-20 急诊入院记录摘要"',
    `content`    TEXT         NOT NULL COMMENT '知识内容（会做分词后写入向量库）',
    `category`   VARCHAR(64)  DEFAULT NULL COMMENT '分类：病历摘要 / 检验结果 / 医嘱记录 / 既往史',
    `source`     VARCHAR(128) DEFAULT NULL COMMENT '来源，例如 HIS 同步 / 手写病程记录',
    `status`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    `created_at` DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_patient` (`tenant_id`, `patient_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='患者隐私知识域（受保护健康信息，禁止跨机构共享）';

-- ---------------------------------------------------------------------------
--  向量库说明（Redis Stack）
--
--  改造后使用两个物理隔离的索引，key 前缀不互相包含，避免按前缀删除时误伤：
--    公共域：索引 emergency_public_index    前缀 embedding:public:
--    患者域：索引 emergency_patient_index   前缀 embedding:patient:
--
--  两个索引都由 VectorStoreConfiguration 在启动时自动创建，且都声明了
--  RediSearch 的 TAG 元字段（domain / tenantId / patientId / category）。
--  这正是不能直接用 Spring AI 自动配置那个 VectorStore 的原因 ——
--  自动配置不会声明元字段，导致 filterExpression 报"未知字段"。
--
--  改造完成后建议执行一次全量重建，让数据落到新前缀下：
--    curl -X POST http://localhost:8080/api/knowledge/rebuild
--
--  历史遗留（改造前）的 embedding:* 数据已不再被读取，可手工清理：
--    redis-cli --scan --pattern 'embedding:*' | grep -v 'embedding:public:' \
--      | grep -v 'embedding:patient:' | xargs -r redis-cli del
--  以及旧的索引：redis-cli FT.DROPINDEX emergency_knowledge_index
-- ---------------------------------------------------------------------------
