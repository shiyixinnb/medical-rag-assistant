-- ============================================================
--  医疗 RAG 问答系统 · 建表脚本
--  用法：mysql -u root -p < schema.sql
--  说明：向量库（Redis）里的内容是这两张表的"副本"，
--        改完 MySQL 需要调用 POST /api/knowledge/rebuild 重建。
-- ============================================================

CREATE DATABASE IF NOT EXISTS emergency_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE emergency_db;

-- ------------------------------------------------------------
-- 公共知识：急救指南、药品说明、操作规范（无隐私，所有租户可见）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS medical_knowledge (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  title       VARCHAR(255) NOT NULL                COMMENT '标题',
  content     LONGTEXT     NOT NULL                COMMENT '知识正文（RAG 检索与注入的主体）',
  category    VARCHAR(64)  DEFAULT NULL            COMMENT '分类：急救指南/操作规范/诊断指南/药品说明',
  tags        VARCHAR(255) DEFAULT NULL            COMMENT '标签，逗号分隔',
  source      VARCHAR(255) DEFAULT NULL            COMMENT '来源（指南名称、版本、页码等）',
  status      TINYINT      NOT NULL DEFAULT 1      COMMENT '1 启用，0 停用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_category (category),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公共医学知识库';

-- ------------------------------------------------------------
-- 患者隐私知识：病历摘要、检验结果、医嘱记录
-- 与公共表物理分开，便于库级授权与审计
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS patient_knowledge (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id   VARCHAR(64)  NOT NULL                COMMENT '机构/院区标识，多租户隔离的强制维度',
  patient_id  VARCHAR(64)  NOT NULL                COMMENT '患者业务标识（脱敏后的就诊号/病案号）',
  title       VARCHAR(255) NOT NULL                COMMENT '标题',
  content     LONGTEXT     NOT NULL                COMMENT '内容（病历摘要/检验结论/医嘱等）',
  category    VARCHAR(64)  DEFAULT NULL            COMMENT '分类：病历摘要/检验结果/医嘱记录/既往史',
  source      VARCHAR(255) DEFAULT NULL            COMMENT '来源，例如 HIS 同步',
  status      TINYINT      NOT NULL DEFAULT 1      COMMENT '1 启用，0 停用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_tenant_patient (tenant_id, patient_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者隐私知识库（按 tenantId 隔离）';
