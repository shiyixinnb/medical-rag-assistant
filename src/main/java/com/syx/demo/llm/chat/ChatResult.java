package com.syx.demo.llm.chat;

import java.util.List;

/**
 * 一次 LLM 调用的结果，连同"怎么走到这一步"的完整决策链。
 *
 * <p>{@code routedModelId} 与 {@code modelId} 是两个不同的东西，别混：
 * 前者是路由<b>打算</b>用的模型，后者是<b>实际</b>给出回答的模型。
 * 两者不一致就说明发生过降级 —— 这正是要监控的指标。
 */
public final class ChatResult {

    private final String content;
    private final String modelId;
    private final String routedModelId;
    private final boolean degraded;
    private final List<ModelAttempt> attempts;
    private final String routeReason;
    private final String intentLabel;
    private final long totalCostMs;

    public ChatResult(String content, String modelId, String routedModelId, boolean degraded,
                      List<ModelAttempt> attempts, String routeReason, String intentLabel, long totalCostMs) {
        this.content = content;
        this.modelId = modelId;
        this.routedModelId = routedModelId;
        this.degraded = degraded;
        this.attempts = List.copyOf(attempts);
        this.routeReason = routeReason;
        this.intentLabel = intentLabel;
        this.totalCostMs = totalCostMs;
    }

    public String getContent() {
        return content;
    }

    public String getModelId() {
        return modelId;
    }

    public String getRoutedModelId() {
        return routedModelId;
    }

    /** 是否发生过主备切换。 */
    public boolean isDegraded() {
        return degraded;
    }

    public List<ModelAttempt> getAttempts() {
        return attempts;
    }

    public String getRouteReason() {
        return routeReason;
    }

    public String getIntentLabel() {
        return intentLabel;
    }

    public long getTotalCostMs() {
        return totalCostMs;
    }
}
