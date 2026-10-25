package com.syx.demo.llm.model;

/**
 * 模型能力标签。路由层按"问题需要什么能力"而不是"模型叫什么名字"来选模型，
 * 这样换服务商（DeepSeek → 院内 Qwen）时路由逻辑完全不用动。
 */
public enum ModelCapability {

    /** 推理密集型：鉴别诊断、多因素权衡、用药冲突推演。对应 DeepSeek-R1 / Qwen-Reasoner 这一类。 */
    REASONING("推理密集"),

    /** 常规问答：事实查询、指南条文、流程说明。对应 GLM-4 / deepseek-chat 这一类。 */
    GENERAL("常规问答"),

    /** 轻量快速：寒暄、澄清、极短提问，追求响应速度与成本。 */
    FAST("轻量快速");

    private final String label;

    ModelCapability(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
