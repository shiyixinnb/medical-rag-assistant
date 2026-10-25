package com.syx.demo.llm.session;

import com.syx.demo.llm.route.QuestionIntent;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 会话状态视图。
 *
 * <p>这里存的不只是"聊过哪些话"，更重要的是<b>判定上下文</b>：
 *
 * <ul>
 *   <li>{@code recentIntents} —— 追问要继承上一轮意图，意图切换要能看出来；</li>
 *   <li>{@code lastQuestion} —— 信息不足触发澄清时，"上一轮问的是什么"要能填进模板；</li>
 *   <li>{@code allowPatientDomain} —— 数据域授权是<b>会话级</b>的，一旦本轮没授权，
 *       整个会话历史里也不能把患者数据带进来。所以它必须持久化在会话上，而不是每次请求临时决定。</li>
 * </ul>
 */
public final class ConversationSession {

    private final String sessionId;
    private final String tenantId;
    private final boolean allowPatientDomain;
    /** 最近的意图，最近的在前。 */
    private final List<QuestionIntent> recentIntents;
    /** 上一轮的原始问题。 */
    private final String lastQuestion;
    /** 已完成的轮次数量。 */
    private final int turnCount;
    private final long createdAt;
    private final long updatedAt;

    public ConversationSession(String sessionId, String tenantId, boolean allowPatientDomain,
                               List<QuestionIntent> recentIntents, String lastQuestion,
                               int turnCount, long createdAt, long updatedAt) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.allowPatientDomain = allowPatientDomain;
        this.recentIntents = recentIntents == null ? new ArrayList<>() : recentIntents;
        this.lastQuestion = lastQuestion;
        this.turnCount = turnCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 全新会话。 */
    public static ConversationSession fresh(String sessionId, String tenantId, boolean allowPatientDomain) {
        long now = System.currentTimeMillis();
        return new ConversationSession(sessionId, tenantId, allowPatientDomain,
                new ArrayList<>(), null, 0, now, now);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    /** 本会话是否获得患者隐私数据域的访问授权。 */
    public boolean isAllowPatientDomain() {
        return allowPatientDomain;
    }

    public List<QuestionIntent> getRecentIntents() {
        return recentIntents;
    }

    public String getLastQuestion() {
        return lastQuestion;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /** 是否是多轮会话中的追问（用于提示模型"这是追问，别重复解释背景"）。 */
    public boolean isFollowUp() {
        return turnCount > 0;
    }

    /** 在上一个状态基础上追加一轮。 */
    public ConversationSession next(String question, QuestionIntent intent, int maxHistory) {
        LinkedList<QuestionIntent> intents = new LinkedList<>(recentIntents);
        intents.addFirst(intent);
        while (intents.size() > Math.max(1, maxHistory)) {
            intents.removeLast();
        }
        return new ConversationSession(sessionId, tenantId, allowPatientDomain,
                new ArrayList<>(intents), question, turnCount + 1,
                createdAt, System.currentTimeMillis());
    }
}
