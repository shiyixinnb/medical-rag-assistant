package com.syx.demo.llm.web;

import lombok.Data;

/**
 * 临床助手问答入参。
 *
 * <p>{@code tenantId} 与 {@code allowPatientDomain} 是数据域授权的两个开关，
 * 刻意做成<b>每次请求显式传入</b>而不是从登录态隐式取 ——
 * 这样"这一问要不要碰患者隐私数据"在代码里是看得见、审得清的一行参数，
 * 而不是埋在一次 curl 调用里看不见的隐式行为。
 */
@Data
public class AskRequest {

    /** 会话 id。同一 id 的多轮提问共享上下文；不传则视为匿名单轮。 */
    private String sessionId;

    /** 用户问题。 */
    private String question;

    /** 机构/院区标识。访问患者隐私数据域时必填，缺失即视为未授权（fail-closed）。 */
    private String tenantId;

    /** 本会话是否允许访问患者隐私数据域。不传时取 app.llm.domain.allow-patient-by-default。 */
    private Boolean allowPatientDomain;

    /** 显式指定模型 id（留空则走能力路由）。主要用于排障与灰度对比。 */
    private String model;
}
