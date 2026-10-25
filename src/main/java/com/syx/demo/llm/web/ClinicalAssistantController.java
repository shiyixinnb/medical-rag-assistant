package com.syx.demo.llm.web;

import com.syx.demo.llm.answer.RagAnswer;
import com.syx.demo.llm.chat.ChatFacade;
import com.syx.demo.llm.config.LlmProperties;
import com.syx.demo.llm.domain.KnowledgeDomain;
import com.syx.demo.llm.guard.GuardSnapshot;
import com.syx.demo.llm.model.ModelDefinition;
import com.syx.demo.llm.model.ModelRegistry;
import com.syx.demo.llm.route.RoutingStrategy;
import com.syx.demo.llm.service.ClinicalAssistantService;
import com.syx.demo.llm.session.ChatTurn;
import com.syx.demo.llm.session.ConversationSession;
import com.syx.demo.llm.session.RedisConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临床助手对外接口。
 *
 * <p>分成两类：
 * <ul>
 *   <li><b>业务接口</b>：{@code /ask} —— 医生实际调用的那个；</li>
 *   <li><b>运维/验收接口</b>：{@code /models}、{@code /health}、{@code /domains} —— 用来回答
 *       "现在到底有哪些模型可用""哪个模型被熔断了""数据域隔离到底生效了没"。
 *       这类接口在医疗系统上线验收时是刚需，因为"看不见的行为"没人敢签字。</li>
 * </ul>
 *
 * <p><b>【阅读导航】</b>业务接口只有两个，先看这两个就够：
 * <ul>
 *   <li>{@link #ask}（约 L79）—— 三行，直接委托给 Service；</li>
 *   <li>{@link #askStream}（约 L110）—— 重点在 <b>SSE 生命周期</b>：订阅 → 逐事件 send →
 *       三个回调（onCompletion / onTimeout / onError）都指向 {@code subscription.dispose()}。</li>
 * </ul>
 * 这个类<b>不做任何业务判断</b>：不判意图、不查库、不拼 Prompt。它只负责
 * "HTTP ↔ Java 对象"和"Flux ↔ SSE 帧"两次转换。读它时如果看到业务逻辑，那就是放错位置了。
 */
@RestController
@RequestMapping("/api/assistant")
public class ClinicalAssistantController {

    private static final Logger log = LoggerFactory.getLogger(ClinicalAssistantController.class);

    /**
     * SSE 超时。要比"模型自身的最长耗时"宽裕得多（推理模型读超时已经 60s），
     * 否则会把正常的慢回答误杀成"流中断"。
     */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private final ClinicalAssistantService assistantService;
    private final ModelRegistry modelRegistry;
    private final ChatFacade chatFacade;
    private final RedisConversationStore conversationStore;
    private final LlmProperties properties;
    private final RoutingStrategy routingStrategy;

    public ClinicalAssistantController(ClinicalAssistantService assistantService,
                                       ModelRegistry modelRegistry,
                                       ChatFacade chatFacade,
                                       RedisConversationStore conversationStore,
                                       LlmProperties properties,
                                       RoutingStrategy routingStrategy) {
        this.assistantService = assistantService;
        this.modelRegistry = modelRegistry;
        this.chatFacade = chatFacade;
        this.conversationStore = conversationStore;
        this.properties = properties;
        this.routingStrategy = routingStrategy;
    }

    /** 临床问答主入口。 */
    @PostMapping("/ask")
    public RagAnswer ask(@RequestBody AskRequest request) {
        return assistantService.ask(request);
    }

    /**
     * 流式问答（SSE）。
     *
     * <p>事件序列：{@code meta}（意图 / 数据域 / 召回清单，在调模型之前就下发）
     * → 若干 {@code delta}（正文增量）→ {@code done}（完整 {@code RagAnswer}）。
     * 拒答时只有 {@code meta + done}，没有 delta。
     *
     * <p><b>线路格式（前端按这个写就行）</b>：每个事件的 {@code data} 都是一个 JSON 对象。
     * <pre>
     * event: meta
     * data: {"sessionId":"...","intentLabel":"急诊分诊","domainScope":"...","citations":[...],"notes":[...]}
     *
     * event: delta
     * data: {"text":"\n- 中暑"}
     *
     * event: done
     * data: {"sessionId":"...","answer":"...","confidence":"高","citations":[...], ...}
     * </pre>
     * 之所以连正文都包成 {@code {"text":...}} 而不是直接下发裸文本，是为了绕开 SSE 规范
     * 对"行首单个空格"的剥离和多行 data 拆分——见
     * {@link com.syx.demo.llm.answer.AssistantStreamEvent#wirePayload()}。
     *
     * <p>用 {@link SseEmitter} 而不是直接返回 {@code Flux}：一是返回 emitter 之后
     * Tomcat 线程立即释放（长耗时的模型调用不再占线程池），二是断开／超时的处理是显式的 ——
     * <b>客户端一断开就必须 dispose 订阅</b>，否则模型还在后台一直生成，白烧钱。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AskRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        Disposable subscription = assistantService.askStream(request).subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.event())
                                .data(event.wirePayload(), MediaType.APPLICATION_JSON));
                    }
                    catch (Exception ex) {
                        // 客户端断开（IOException）或序列化失败：停掉上游，否则会一直生成下去
                        log.debug("[Assistant] SSE 推送中断，已停止生成：{}", ex.getMessage());
                        emitter.complete();
                    }
                },
                error -> {
                    log.warn("[Assistant] SSE 流异常终止：{}", error.getMessage());
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("message", String.valueOf(error.getMessage())),
                                        MediaType.APPLICATION_JSON));
                    }
                    catch (Exception ignored) {
                        // 连接已经没了，这里没有可做的事
                    }
                    emitter.complete();
                },
                emitter::complete);

        // 三个回调都指向同一个动作：上游订阅必须被释放
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            log.warn("[Assistant] SSE 超时（{}ms），已停止生成", STREAM_TIMEOUT_MS);
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(e -> subscription.dispose());
        return emitter;
    }

    /**
     * 模型注册表：有哪些模型、什么能力、以及主备降级链怎么走的。
     *
     * <p>被跳过的配置也会列出来（含原因），这样"我明明配了 Qwen 为什么不用它"
     * 这种问题不需要翻日志。
     */
    @GetMapping("/models")
    public Map<String, Object> models() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelDefinition d : modelRegistry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("displayName", d.getDisplayName());
            item.put("provider", d.getProvider().name());
            item.put("baseUrl", d.getMaskedBaseUrl());
            item.put("model", d.getModel());
            item.put("capability", d.getCapability().name());
            item.put("capabilityLabel", d.getCapability().getLabel());
            item.put("priority", d.getPriority());
            item.put("connectTimeoutSeconds", d.getConnectTimeoutSeconds());
            item.put("readTimeoutSeconds", d.getReadTimeoutSeconds());
            item.put("remark", d.getRemark());
            item.put("fallbackChain", mapChain(d.getId()));
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultModel", properties.getDefaultModel());
        result.put("routingStrategy", routingStrategy.name());
        result.put("routingEnabled", properties.getRouting().isEnabled());
        result.put("models", items);
        result.put("skipped", modelRegistry.getSkipped());
        result.put("breakerBackend", chatFacade.getGuard().backend());
        result.put("retry", Map.of(
                "maxAttempts", properties.getRetry().getMaxAttempts(),
                "backoffMs", properties.getRetry().getBackoffMs()));
        return result;
    }

    /** 各模型熔断状态。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, GuardSnapshot> snapshots = chatFacade.getGuard().snapshots();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelDefinition d : modelRegistry.all()) {
            GuardSnapshot s = snapshots.get(d.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelId", d.getId());
            item.put("circuitState", s == null ? "CLOSED" : s.getState());
            item.put("calls", s == null ? 0 : s.getCalls());
            item.put("failures", s == null ? 0 : s.getFailures());
            item.put("blocked", s == null ? 0 : s.getBlocked());
            item.put("failureRate", s == null ? 0d : s.getFailureRate());
            item.put("note", s == null ? "尚无调用记录" : s.getNote());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("breakerBackend", chatFacade.getGuard().backend());
        result.put("models", items);
        return result;
    }

    /** 数据域说明与授权策略，用于验收"隔离到底是怎么做的"。 */
    @GetMapping("/domains")
    public Map<String, Object> domains() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (KnowledgeDomain d : KnowledgeDomain.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", d.getKey());
            item.put("label", d.getLabel());
            item.put("description", d.getDescription());
            item.put("index", d == KnowledgeDomain.PATIENT
                    ? com.syx.demo.llm.domain.VectorStoreConfiguration.PATIENT_INDEX
                    : com.syx.demo.llm.domain.VectorStoreConfiguration.PUBLIC_INDEX);
            item.put("prefix", d == KnowledgeDomain.PATIENT
                    ? com.syx.demo.llm.domain.VectorStoreConfiguration.PATIENT_PREFIX
                    : com.syx.demo.llm.domain.VectorStoreConfiguration.PUBLIC_PREFIX);
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domains", items);
        result.put("allowPatientByDefault", properties.getDomain().isAllowPatientByDefault());
        result.put("policy", "fail-closed：患者域需要 tenantId + 显式授权；未授权时不检索、不调用模型，直接拒答");
        return result;
    }

    /** 查看会话状态：意图历史、轮次、剩余存活时间、最近几轮内容。 */
    @GetMapping("/session/{sessionId}")
    public Map<String, Object> session(@PathVariable String sessionId) {
        ConversationSession session = conversationStore.loadOrCreate(sessionId, null, false);
        List<ChatTurn> turns = conversationStore.recentTurns(sessionId, properties.getSession().getHistoryTurns());

        List<Map<String, Object>> turnViews = new ArrayList<>();
        for (ChatTurn t : turns) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("role", t.getRole().name());
            view.put("intent", t.getIntent());
            view.put("modelId", t.getModelId());
            view.put("degraded", t.isDegraded());
            view.put("at", t.getAt());
            // 只回预览，完整内容对前端没有价值，还会把隐私数据又复制到浏览器一份
            view.put("preview", preview(t.getText()));
            turnViews.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getSessionId());
        result.put("tenantId", session.getTenantId());
        result.put("allowPatientDomain", session.isAllowPatientDomain());
        result.put("turnCount", session.getTurnCount());
        result.put("recentIntents", session.getRecentIntents());
        result.put("lastQuestion", session.getLastQuestion());
        result.put("ttlSeconds", conversationStore.ttlSeconds(sessionId));
        result.put("recentTurns", turnViews);
        return result;
    }

    /** 清空会话（新对话）。 */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        conversationStore.clear(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("cleared", true);
        return result;
    }

    private List<String> mapChain(String modelId) {
        List<String> ids = new ArrayList<>();
        modelRegistry.resolveChain(modelId).forEach(m -> ids.add(m.getId()));
        return ids;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }
}
