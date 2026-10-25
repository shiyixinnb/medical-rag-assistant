package com.syx.demo.llm.route;

import java.util.List;

/**
 * 问题意图。它同时承担两个职责，这是刻意的设计：
 *
 * <ol>
 *   <li><b>路由</b>：{@link #isPreferReasoning()} 决定优先走 R1 这类推理模型还是 GLM 这类常规模型；</li>
 *   <li><b>数据域约束</b>：{@link #isRequiresPatientDomain()} 决定这次请求能不能碰患者隐私数据。</li>
 * </ol>
 *
 * 把两件事绑在同一个判定上，是因为它们本来就是同一个问题的两面 ——
 * "这次问的是通用医学知识还是这个病人的情况"。
 * 分开判定很容易出现"路由认为是常识题、域判定却放开了病历"这种自相矛盾的组合。
 */
public enum QuestionIntent {

    /** 急诊分诊、危重识别、生命体征判断。要求"先给处置、再讲依据"，且必须走推理能力强的模型。 */
    EMERGENCY_TRIAGE("急诊分诊", true, false,
            "危重识别与抢救优先级判断，容错率最低"),

    /** 药品说明、剂量、配伍禁忌、相互作用。依据相对确定，常规模型 + 药品知识域即可。 */
    DRUG_QUERY("用药咨询", false, false,
            "剂量与配伍属于强事实问题，重在引用准确"),

    /** 诊疗指南、操作规范、临床路径查询。片段化检索 + 常规模型。 */
    GUIDELINE_QUERY("指南规范", false, false,
            "条文检索类问题，能命中原文就不需要模型推理"),

    /** 明确指向某个患者的病历、检验、既往史、用药记录 —— 只能访问患者隐私域。 */
    PATIENT_RECORD_QUERY("患者病历", true, true,
            "涉及具体患者数据，必须限定在本院患者数据域内回答"),

    /** 鉴别诊断、病理机制、多因素权衡。典型的推理密集问题，优先 R1。 */
    COMPLEX_REASONING("复杂推理", true, false,
            "需要多步推理与权衡，普通模型容易答得又快又错"),

    /** 通用医学问答。 */
    GENERAL_QA("通用问答", false, false, "普通知识性提问"),

    /** 寒暄、闲聊、与业务无关的问题。走最便宜的模型，甚至可以引导用户回到正题。 */
    CHITCHAT("闲聊", false, false, "与临床业务无关的输入");

    private final String label;
    private final boolean preferReasoning;
    private final boolean requiresPatientDomain;
    private final String description;

    QuestionIntent(String label, boolean preferReasoning, boolean requiresPatientDomain, String description) {
        this.label = label;
        this.preferReasoning = preferReasoning;
        this.requiresPatientDomain = requiresPatientDomain;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    /** 是否优先使用推理型模型。 */
    public boolean isPreferReasoning() {
        return preferReasoning;
    }

    /** 是否必然涉及患者隐私数据域。 */
    public boolean isRequiresPatientDomain() {
        return requiresPatientDomain;
    }

    public String getDescription() {
        return description;
    }

    /** 用于日志的固定关键字命中描述。 */
    public static String describe(List<String> hits) {
        return hits == null || hits.isEmpty() ? "无关键字命中" : "命中关键字 " + hits;
    }
}
