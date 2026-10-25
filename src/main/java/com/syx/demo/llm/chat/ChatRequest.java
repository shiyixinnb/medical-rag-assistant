package com.syx.demo.llm.chat;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 LLM 调用的入参。
 *
 * <p>用 builder 而不是构造器，是因为参数里有好几个"可选但语义不同"的开关
 * （显式指定模型、是否带历史、历史条数），位置传参很容易写错。
 */
public final class ChatRequest {

    /** 会话 id，同时用作 Redis 会话键与日志追踪 id。 */
    private final String sessionId;
    /** 完整的 system 文本：角色设定 + RAG 上下文 + 输出约束，由 PromptTemplateService 渲染好。 */
    private final String systemPrompt;
    /** 本轮用户问题。 */
    private final String userMessage;
    /** 历史消息（已按时间正序）。 */
    private final List<Message> history;
    /** 显式指定模型；为空则由路由策略决定。 */
    private final String explicitModelId;
    /** 意图判定结果，用于路由与日志。 */
    private final com.syx.demo.llm.route.IntentSignal intent;

    private ChatRequest(Builder b) {
        this.sessionId = b.sessionId;
        this.systemPrompt = b.systemPrompt;
        this.userMessage = b.userMessage;
        this.history = b.history == null ? List.of() : List.copyOf(b.history);
        this.explicitModelId = b.explicitModelId;
        this.intent = b.intent;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public List<Message> getHistory() {
        return history;
    }

    public String getExplicitModelId() {
        return explicitModelId;
    }

    public com.syx.demo.llm.route.IntentSignal getIntent() {
        return intent;
    }

    public static final class Builder {
        private String sessionId = "anonymous";
        private String systemPrompt = "";
        private String userMessage = "";
        private List<Message> history = new ArrayList<>();
        private String explicitModelId;
        private com.syx.demo.llm.route.IntentSignal intent;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder history(List<Message> history) {
            this.history = history;
            return this;
        }

        public Builder explicitModelId(String explicitModelId) {
            this.explicitModelId = explicitModelId;
            return this;
        }

        public Builder intent(com.syx.demo.llm.route.IntentSignal intent) {
            this.intent = intent;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}
