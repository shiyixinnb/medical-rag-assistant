package com.syx.demo.llm.session;

/**
 * 一轮对话。既用于给模型还原上下文，也用于审计"这句话是谁问的、哪个模型答的"。
 *
 * <p>不直接存 Spring AI 的 {@code Message} 对象，是因为 Message 里还挂着 toolCalls、metadata 等
 * 我们不需要（也可能随版本变化）的字段，直接序列化进 Redis 会带来升级兼容问题。
 * 这里只保留"角色 + 文本 + 元信息"这三个稳定字段，反序列化时再构造 Message。
 */
public final class ChatTurn {

    public enum Role {
        USER, ASSISTANT
    }

    private Role role;
    /** 文本内容。 */
    private String text;
    /** 意图标签（仅 USER 轮有值），用于意图切换分析。 */
    private String intent;
    /** 实际回答的模型 id（仅 ASSISTANT 轮有值）。 */
    private String modelId;
    /** 是否降级产生。 */
    private boolean degraded;
    /** 时间戳（毫秒）。 */
    private long at;

    /** Jackson 反序列化需要无参构造。 */
    public ChatTurn() {
    }

    public ChatTurn(Role role, String text, String intent, String modelId, boolean degraded, long at) {
        this.role = role;
        this.text = text;
        this.intent = intent;
        this.modelId = modelId;
        this.degraded = degraded;
        this.at = at;
    }

    public static ChatTurn user(String text, String intent) {
        return new ChatTurn(Role.USER, text, intent, null, false, System.currentTimeMillis());
    }

    public static ChatTurn assistant(String text, String modelId, boolean degraded) {
        return new ChatTurn(Role.ASSISTANT, text, null, modelId, degraded, System.currentTimeMillis());
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public long getAt() {
        return at;
    }

    public void setAt(long at) {
        this.at = at;
    }
}
