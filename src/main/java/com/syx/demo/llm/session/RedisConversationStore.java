package com.syx.demo.llm.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syx.demo.llm.config.LlmProperties;
import com.syx.demo.llm.route.QuestionIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Redis 的多轮会话存储。
 *
 * <p>数据结构（前缀来自 {@code app.llm.session.key-prefix}）：
 *
 * <pre>
 *   {prefix}session:{id}  HASH   intentHistory / lastQuestion / tenantId / allowPatientDomain / turnCount / createdAt
 *   {prefix}turns:{id}    LIST   每轮一条 ChatTurn 的 JSON（RPUSH 追加，LTRIM 裁剪）
 * </pre>
 *
 * <p>两个实现上的取舍：
 *
 * <ol>
 *   <li><b>为什么不用 Spring AI 的 {@code MessageChatMemoryAdvisor} + 自实现 {@code ChatMemory}？</b>
 *       因为那个方案只能存"消息列表"，存不下意图、租户、数据域授权这些会话级判定依据；
 *       而数据域授权必须跟着会话走（见 {@link ConversationSession} 的说明）。
 *       用两套存储去分别存消息和元数据，还会带来两者不一致的问题。所以这里用一套结构同时承载两者。</li>
 *   <li><b>Redis 故障时降级成"无记忆"而不是报错。</b>急诊问答的主链路是"能不能回答"，
 *       多轮上下文是增强项。Redis 挂了还让整个问答失败，是把次要依赖当成了主要依赖。
 *       所以所有 Redis 操作都吞掉异常、打警告日志，返回空上下文继续往下走。</li>
 * </ol>
 *
 * <p><b>【阅读导航】</b>五个公开方法，按调用顺序读：
 * <ol>
 *   <li>{@link #loadOrCreate} —— 进链路第一件事，读会话（顺便决定本轮有没有患者域授权）；</li>
 *   <li>{@link #historyMessages} —— 拼 {@code ChatRequest} 时调用，把历史转成 Spring AI 的 Message；</li>
 *   <li>{@link #appendTurn} —— 收尾时写入本轮问答，并对 LIST 做 LTRIM 裁剪。</li>
 * </ol>
 * {@code recentTurns} / {@code clear} 是给运维接口用的，可以先跳过。
 *
 * <p>读的时候留意两个"静默降级"：读失败返回空会话、写失败只打日志。
 * 这不是偷懒——急诊问答的主链路是"能不能回答"，历史上下文没了也要能答。
 */
@Component
public class RedisConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);

    private static final String FIELD_INTENT_HISTORY = "intentHistory";
    private static final String FIELD_LAST_QUESTION = "lastQuestion";
    private static final String FIELD_TENANT = "tenantId";
    private static final String FIELD_ALLOW_PATIENT = "allowPatientDomain";
    private static final String FIELD_TURN_COUNT = "turnCount";
    private static final String FIELD_CREATED_AT = "createdAt";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final LlmProperties properties;

    public RedisConversationStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                                  LlmProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 载入会话；不存在则按传入的授权参数创建一个新会话。
     *
     * <p>注意授权是<b>取并集</b>：会话建立时授权过患者域，后续轮次就不需要重复授权；
     * 反过来，会话没授权过、本轮也没授权，那就依然是没授权。
     */
    public ConversationSession loadOrCreate(String sessionId, String tenantId, boolean allowPatientDomain) {
        String id = normalize(sessionId);
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(sessionKey(id));
            if (raw == null || raw.isEmpty()) {
                return ConversationSession.fresh(id, tenantId, allowPatientDomain);
            }
            List<QuestionIntent> intents = new ArrayList<>();
            Object history = raw.get(FIELD_INTENT_HISTORY);
            if (history != null) {
                for (String name : objectMapper.readValue(history.toString(), new TypeReference<List<String>>() {
                })) {
                    try {
                        intents.add(QuestionIntent.valueOf(name));
                    }
                    catch (IllegalArgumentException ignored) {
                        // 枚举被重命名过就跳过，不要让历史数据把当前会话搞崩
                    }
                }
            }
            boolean storedAllow = Boolean.parseBoolean(String.valueOf(raw.get(FIELD_ALLOW_PATIENT)));
            long createdAt = raw.get(FIELD_CREATED_AT) == null
                    ? System.currentTimeMillis()
                    : Long.parseLong(String.valueOf(raw.get(FIELD_CREATED_AT)));
            Object lastQ = raw.get(FIELD_LAST_QUESTION);
            Object turns = raw.get(FIELD_TURN_COUNT);

            renewTtl(id);
            return new ConversationSession(id,
                    tenantId != null ? tenantId : str(raw.get(FIELD_TENANT)),
                    storedAllow || allowPatientDomain,
                    intents,
                    lastQ == null ? null : lastQ.toString(),
                    turns == null ? 0 : Integer.parseInt(turns.toString()),
                    createdAt, System.currentTimeMillis());
        }
        catch (Exception ex) {
            // Redis 不可用 / 数据损坏都不能影响问答主链路
            warnOnce("载入会话失败，本轮以无历史上下文继续", ex);
            return ConversationSession.fresh(id, tenantId, allowPatientDomain);
        }
    }

    /** 取最近若干轮对话，按时间正序返回（供 prompt 使用）。 */
    public List<ChatTurn> recentTurns(String sessionId, int maxTurns) {
        String id = normalize(sessionId);
        try {
            Long size = redis.opsForList().size(turnsKey(id));
            if (size == null || size == 0) {
                return List.of();
            }
            int want = Math.max(1, maxTurns) * 2; // 一轮 = user + assistant
            long start = Math.max(0, size - want);
            List<String> raw = redis.opsForList().range(turnsKey(id), start, size - 1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<ChatTurn> turns = new ArrayList<>(raw.size());
            for (String json : raw) {
                turns.add(objectMapper.readValue(json, ChatTurn.class));
            }
            return turns;
        }
        catch (Exception ex) {
            warnOnce("读取会话历史失败，本轮不带历史消息", ex);
            return List.of();
        }
    }

    /**
     * 把会话历史转换成 Spring AI 的 Message 列表。
     *
     * <p>只取最近 {@code historyTurns} 轮，并且<b>保证以 USER 开头</b>：
     * 裁剪后有可能是从一条 ASSISTANT 消息开始，那样的历史会让模型误解上下文顺序。
     */
    public List<Message> historyMessages(String sessionId) {
        int maxTurns = Math.max(1, properties.getSession().getHistoryTurns());
        List<ChatTurn> turns = recentTurns(sessionId, maxTurns);
        if (turns.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(turns.size());
        for (ChatTurn turn : turns) {
            if (turn.getRole() == ChatTurn.Role.USER) {
                messages.add(new UserMessage(turn.getText()));
            }
            else if (turn.getRole() == ChatTurn.Role.ASSISTANT) {
                messages.add(new AssistantMessage(turn.getText()));
            }
        }
        // 裁掉开头连续的 assistant 消息，保证历史从 user 开始
        int firstUser = 0;
        while (firstUser < messages.size() && !(messages.get(firstUser) instanceof UserMessage)) {
            firstUser++;
        }
        return firstUser >= messages.size() ? List.of() : messages.subList(firstUser, messages.size());
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 追加一轮完整对话（用户问题 + 模型回答），并更新会话元数据。
     *
     * <p>写入顺序是"先 turns 后 session"：万一中间挂掉，最坏情况是元数据略有滞后，
     * 而不是出现"元数据说聊过 5 轮、实际只有 4 轮消息"这种会让裁剪逻辑错乱的组合。
     */
    public void appendTurn(ConversationSession session, String question, QuestionIntent intent,
                           String answer, String modelId, boolean degraded) {
        String id = normalize(session.getSessionId());
        try {
            List<String> payload = List.of(
                    objectMapper.writeValueAsString(ChatTurn.user(question, intent == null ? null : intent.name())),
                    objectMapper.writeValueAsString(ChatTurn.assistant(answer, modelId, degraded)));
            redis.opsForList().rightPushAll(turnsKey(id), payload);

            // 裁剪：只保留最近 N 条消息，避免长会话把 Redis 和 prompt 都撑爆
            long keep = Math.max(2, properties.getSession().getMaxStoredMessages());
            redis.opsForList().trim(turnsKey(id), -keep, -1);

            ConversationSession next = session.next(question, intent,
                    properties.getSession().getHistoryTurns());

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(FIELD_INTENT_HISTORY, objectMapper.writeValueAsString(
                    next.getRecentIntents().stream().map(Enum::name).collect(Collectors.toList())));
            meta.put(FIELD_LAST_QUESTION, question == null ? "" : question);
            meta.put(FIELD_TENANT, session.getTenantId() == null ? "" : session.getTenantId());
            meta.put(FIELD_ALLOW_PATIENT, String.valueOf(session.isAllowPatientDomain()));
            meta.put(FIELD_TURN_COUNT, String.valueOf(next.getTurnCount()));
            meta.put(FIELD_CREATED_AT, String.valueOf(session.getCreatedAt()));
            redis.opsForHash().putAll(sessionKey(id), meta);
            renewTtl(id);
        }
        catch (Exception ex) {
            warnOnce("写入会话失败，本轮上下文不会被记住", ex);
        }
    }

    /** 清空会话（用户点"新对话"时调用）。 */
    public void clear(String sessionId) {
        String id = normalize(sessionId);
        try {
            redis.delete(List.of(sessionKey(id), turnsKey(id)));
        }
        catch (Exception ex) {
            warnOnce("清空会话失败", ex);
        }
    }

    /** 会话剩余存活时间（秒），不存在返回 -2，无过期返回 -1。 */
    public long ttlSeconds(String sessionId) {
        try {
            Long ttl = redis.getExpire(sessionKey(normalize(sessionId)));
            return ttl == null ? -2 : ttl;
        }
        catch (Exception ex) {
            return -2;
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private void renewTtl(String sessionId) {
        Duration ttl = Duration.ofMinutes(Math.max(1, properties.getSession().getTtlMinutes()));
        redis.expire(sessionKey(sessionId), ttl);
        redis.expire(turnsKey(sessionId), ttl);
    }

    private String sessionKey(String id) {
        return properties.getSession().getKeyPrefix() + "session:" + id;
    }

    private String turnsKey(String id) {
        return properties.getSession().getKeyPrefix() + "turns:" + id;
    }

    private String normalize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "anonymous";
        }
        String trimmed = sessionId.trim();
        // 会话 id 来自前端，做一次白名单过滤，避免拼出奇怪的 key 或覆盖别人的会话
        return trimmed.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 同一个故障反复刷屏没意义，这里只按类名去重打一次。 */
    private final java.util.Set<String> warned = Collections.synchronizedSet(new java.util.HashSet<>());

    private void warnOnce(String message, Exception ex) {
        String key = message + "|" + ex.getClass().getName();
        if (warned.add(key)) {
            log.warn("[LLM-Session] {}（同类问题只提示一次）：{}: {}", message,
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
        else {
            log.debug("[LLM-Session] {}：{}", message, ex.getMessage());
        }
    }
}
