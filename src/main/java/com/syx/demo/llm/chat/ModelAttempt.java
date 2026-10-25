package com.syx.demo.llm.chat;

/**
 * 单个模型的单次尝试记录。整个"路由 → 重试 → 降级"过程都会留下痕迹，
 * 响应里带上它，医生/运维才能知道"这个回答到底是哪个模型给的、中间切过几次"。
 */
public final class ModelAttempt {

    private final String modelId;
    private final int attempt;
    private final boolean success;
    private final boolean blocked;
    private final long costMs;
    private final String errorType;
    private final String errorMessage;

    private ModelAttempt(String modelId, int attempt, boolean success, boolean blocked,
                         long costMs, String errorType, String errorMessage) {
        this.modelId = modelId;
        this.attempt = attempt;
        this.success = success;
        this.blocked = blocked;
        this.costMs = costMs;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
    }

    public static ModelAttempt success(String modelId, int attempt, long costMs) {
        return new ModelAttempt(modelId, attempt, true, false, costMs, null, null);
    }

    public static ModelAttempt failure(String modelId, int attempt, long costMs, Throwable error) {
        String msg = error == null ? "" : error.getMessage();
        if (msg != null && msg.length() > 300) {
            msg = msg.substring(0, 300) + "...";
        }
        return new ModelAttempt(modelId, attempt, false, false, costMs,
                error == null ? "unknown" : error.getClass().getSimpleName(), msg);
    }

    public static ModelAttempt blocked(String modelId) {
        return new ModelAttempt(modelId, 0, false, true, 0, "CircuitOpen", "熔断打开，跳过该模型");
    }

    public String getModelId() {
        return modelId;
    }

    public int getAttempt() {
        return attempt;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public long getCostMs() {
        return costMs;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /** 供日志/前端展示的一行摘要。 */
    public String describe() {
        if (success) {
            return modelId + " 第" + attempt + "次成功(" + costMs + "ms)";
        }
        if (blocked) {
            return modelId + " 熔断跳过";
        }
        return modelId + " 第" + attempt + "次失败(" + errorType + ": " + errorMessage + ")";
    }
}
