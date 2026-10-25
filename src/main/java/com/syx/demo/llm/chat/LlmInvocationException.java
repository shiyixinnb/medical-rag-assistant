package com.syx.demo.llm.chat;

/**
 * 所有候选模型都失败时抛出。
 *
 * <p>为什么不直接返回一段"服务繁忙"的文案？因为上层需要区分
 * "模型全挂了"和"知识库里没依据"这两种情况 —— 前者应该提示换渠道重试，
 * 后者是业务结论。异常里带着完整的尝试记录，方便定位到底是超时、限流还是鉴权问题。
 */
public class LlmInvocationException extends RuntimeException {

    private final java.util.List<ModelAttempt> attempts;

    public LlmInvocationException(String message, java.util.List<ModelAttempt> attempts, Throwable cause) {
        super(message, cause);
        this.attempts = attempts == null ? java.util.List.of() : java.util.List.copyOf(attempts);
    }

    public java.util.List<ModelAttempt> getAttempts() {
        return attempts;
    }

    /** 人可读的失败链路摘要。 */
    public String describeAttempts() {
        StringBuilder sb = new StringBuilder();
        for (ModelAttempt a : attempts) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(a.describe());
        }
        return sb.length() == 0 ? "无尝试记录" : sb.toString();
    }
}
