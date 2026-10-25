package com.syx.demo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 患者隐私知识（病历、检验检查、医嘱等）。
 *
 * <p>刻意与 {@link MedicalKnowledge} 分成两张表，而不是在一张表里加个 type 字段：
 * 表级隔离让"DBA 只给某院授权 patient_knowledge 的库"成为可能，
 * 也让任何时候的 {@code select * from medical_knowledge} 都不可能带出患者隐私。
 *
 * <p>落库时 {@code tenantId} 是强制维度：它是"回答不泄露其他医院私有数据"的最后一道防线，
 * 因为向量库检索出来之后还会在 Java 里再校验一次租户归属。
 */
@Data
@TableName("patient_knowledge")
public class PatientKnowledge {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构/院区标识。多租户共用一套 Redis 时靠它隔离。 */
    private String tenantId;

    /** 患者业务标识（建议用脱敏后的就诊号/病案号，不要放身份证号、手机号）。 */
    private String patientId;

    private String title;

    /** 知识内容（可以是摘要、结论、出院小结要点等）。 */
    private String content;

    /** 分类：病历摘要 / 检验结果 / 医嘱记录 / 既往史 ... */
    private String category;

    /** 来源，例如"HIS 系统同步"、"手写病程记录"。 */
    private String source;

    /** 1 启用 0 停用。 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
