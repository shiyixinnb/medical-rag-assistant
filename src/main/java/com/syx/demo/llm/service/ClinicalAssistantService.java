package com.syx.demo.llm.service;

import com.syx.demo.llm.answer.AnswerMetaParser;
import com.syx.demo.llm.answer.AssistantStreamEvent;
import com.syx.demo.llm.answer.RagAnswer;
import com.syx.demo.llm.chat.ChatFacade;
import com.syx.demo.llm.chat.ChatRequest;
import com.syx.demo.llm.chat.ChatResult;
import com.syx.demo.llm.chat.ChatStreamMeta;
import com.syx.demo.llm.chat.LlmInvocationException;
import com.syx.demo.llm.chat.ModelAttempt;
import com.syx.demo.llm.config.LlmProperties;
import com.syx.demo.llm.domain.DomainAwareRetriever;
import com.syx.demo.llm.domain.KnowledgeDomain;
import com.syx.demo.llm.domain.PatientIdExtractor;
import com.syx.demo.llm.domain.RetrievalResult;
import com.syx.demo.llm.domain.RetrievedSnippet;
import com.syx.demo.llm.prompt.PromptTemplateService;
import com.syx.demo.llm.route.IntentClassifier;
import com.syx.demo.llm.route.IntentSignal;
import com.syx.demo.llm.route.QuestionIntent;
import com.syx.demo.llm.session.ConversationSession;
import com.syx.demo.llm.session.RedisConversationStore;
import com.syx.demo.llm.web.AskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 临床助手 —— 把"意图判定 → 数据域判定 → 检索 → Prompt 组装 → 模型调用 → 结果结构化"
 * 串成一条完整链路。
 *
 * <p>对外提供两条等价路径：
 * <ul>
 *   <li>{@link #ask(AskRequest)} —— 一次性返回（适合脚本、内部调用、接口联调）；</li>
 *   <li>{@link #askStream(AskRequest)} —— 逐字返回（医生端实际使用，首字延迟决定体验）。</li>
 * </ul>
 * 两条路径<b>共用 {@link #prepare(AskRequest, long)} 与 {@link #buildAnswer} 两个阶段</b>，
 * 所以"数据域裁决""拒答条件""引用校验"不会出现两套实现各说各话的情况 ——
 * 这是这个类最需要守住的一致性。
 *
 * <h3>数据域策略（fail-closed 的核心）</h3>
 *
 * <p>规则只有三条，但每条都是"宁可少答，不可错答"：
 *
 * <table border="1">
 *   <tr><th>场景</th><th>行为</th><th>理由</th></tr>
 *   <tr>
 *     <td>问题针对具体患者，但患者域<b>未授权</b></td>
 *     <td>直接拒答，<b>不调用模型</b>、不检索</td>
 *     <td>没有任何数据被取出，也就不存在泄露可能；同时避免模型用通用知识
 *         编出一个"像是这个患者的答案"</td>
 *   </tr>
 *   <tr>
 *     <td>问题针对具体患者，患者域已授权，但<b>本院查不到该患者资料</b></td>
 *     <td>拒答</td>
 *     <td>医生看到一段有依据的临床建议时，很难分辨它到底是不是针对这个患者的。
 *         患者数据缺失时必须明确说"没有"，而不是拿通用指南顶上</td>
 *   </tr>
 *   <tr>
 *     <td>问题里<b>点名了别的机构的患者</b>（如拿 {@code tenantId=HOSP-A} 去问 {@code P-9001}）</td>
 *     <td>拒答</td>
 *     <td>由 {@link PatientIdExtractor} 抽出患者标识后把检索收敛到该标识，
 *         必然查空 → 落到上一条的拒答分支。这样拒答是<b>确定的</b>，
 *         不需要依赖模型自己发现"资料里没有这个人"，也就不存在被上下文带偏去硬答的可能</td>
 *   </tr>
 *   <tr>
 *     <td>其他所有情况</td>
 *     <td>只检索公共知识域</td>
 *     <td>公共问题用不到患者数据，少带数据 = 更小的暴露面</td>
 *   </tr>
 * </table>
 *
 * <p>另外，检索不到资料<b>不等于</b>拒答（公共域场景）：这种情况下仍然调用模型，
 * 但 Prompt 会明确告诉它"没有资料、不许编造具体数值"，让模型去解释原因并引导用户换个问法。
 * 这比硬编码一句冷冰冰的"无结果"对医生更有用。
 *
 * <p><b>【阅读导航】这个类是整条链路的主干道，按下面顺序读，别从第一行顺着看：</b>
 * <ol>
 *   <li>{@link #ask}（约 L119，只有 13 行）—— 先看清主干到底有多短：准备 → 调模型 → 收尾；</li>
 *   <li>{@link #prepare}（约 L220）—— <b>全项目最重要的 90 行</b>，调模型之前的全部判断都在这里，
 *       重点看注释里的 ①-⑦ 编号；</li>
 *   <li>{@link #buildAnswer}（约 L334）—— 拿到模型输出之后做了什么（剥 META、校验引用、写会话）；</li>
 *   <li>{@link #askStream}（约 L151）—— 最后看流式，你会发现它只是把"调模型"那一段换成了 Flux。</li>
 * </ol>
 *
 * <p><b>上游</b>是 {@code ClinicalAssistantController}（只做 HTTP 转换）；
 * <b>下游</b>是 {@code IntentClassifier}（判意图）、{@code DomainAwareRetriever}（找资料）、
 * {@code PromptTemplateService}（拼纸条）、{@code ChatFacade}（调模型）、
 * {@code RedisConversationStore}（记上下文）。
 * 一句话：<b>这个类不干活，只决定干活的顺序</b>。
 */
@Service
public class ClinicalAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalAssistantService.class);

    private final IntentClassifier intentClassifier;
    private final DomainAwareRetriever retriever;
    private final PatientIdExtractor patientIdExtractor;
    private final RedisConversationStore conversationStore;
    private final ChatFacade chatFacade;
    private final PromptTemplateService promptService;
    private final LlmProperties properties;

    public ClinicalAssistantService(IntentClassifier intentClassifier,
                                    DomainAwareRetriever retriever,
                                    PatientIdExtractor patientIdExtractor,
                                    RedisConversationStore conversationStore,
                                    ChatFacade chatFacade,
                                    PromptTemplateService promptService,
                                    LlmProperties properties) {
        this.intentClassifier = intentClassifier;
        this.retriever = retriever;
        this.patientIdExtractor = patientIdExtractor;
        this.conversationStore = conversationStore;
        this.chatFacade = chatFacade;
        this.promptService = promptService;
        this.properties = properties;
    }

    // ================================================================== 一次性返回

    /**
     * 同步问答。方法体只有三步：{@code prepare} → {@code chatFacade.call} → {@code buildAnswer}。
     *
     * <p>所有复杂度都被这三步各自的实现吃掉了 —— 这正是"编排层"该有的样子：
     * 读它能一眼看清顺序，读细节再去各自的类里钻。
     */
    public RagAnswer ask(AskRequest request) {
        long start = System.currentTimeMillis();

        Prepared p = prepare(request, start);
        if (p.stage() == Stage.REFUSED) {
            return p.refusal();
        }

        ChatResult result;
        try {
            result = chatFacade.call(chatRequestOf(p));
        }
        catch (LlmInvocationException ex) {
            log.error("[Assistant] 模型全部不可用：{}", ex.describeAttempts());
            return modelDownAnswer(p, ex, System.currentTimeMillis() - start);
        }

        return buildAnswer(p, result.getContent(),
                result.getModelId(), result.getRoutedModelId(), result.isDegraded(),
                result.getAttempts(), result.getRouteReason(),
                System.currentTimeMillis() - start);
    }

    // ================================================================== 流式返回

    /**
     * 流式问答。事件顺序固定为：
     * {@code meta}（调模型前就能给出的过程信息）→ 若干 {@code delta}（正文增量）→ {@code done}（完整结果）。
     *
     * <p>拒答路径同样返回 {@code meta + done}，只是没有任何 delta ——
     * 这样前端不需要为"拒答"单独写一套分支。
     */
    public Flux<AssistantStreamEvent> askStream(AskRequest request) {
        long start = System.currentTimeMillis();

        Prepared p = prepare(request, start);
        if (p.stage() == Stage.REFUSED) {
            return Flux.just(AssistantStreamEvent.meta(metaPayload(p)),
                    AssistantStreamEvent.done(p.refusal()));
        }

        MetaStreamFilter filter = new MetaStreamFilter();
        StringBuilder body = new StringBuilder();
        AtomicReference<ChatStreamMeta> metaHolder = new AtomicReference<>();

        Flux<AssistantStreamEvent> deltas = chatFacade.stream(chatRequestOf(p))
                .<AssistantStreamEvent>handle((ev, sink) -> {
                    if (ev.isDelta()) {
                        String safe = filter.accept(ev.delta());
                        if (!safe.isEmpty()) {
                            body.append(safe);
                            sink.next(AssistantStreamEvent.delta(safe));
                        }
                        return;
                    }
                    metaHolder.set(ev.meta());
                })
                .concatWith(Flux.defer(() -> {
                    List<AssistantStreamEvent> tail = new ArrayList<>();

                    // 流结束时把缓冲区剩余内容补发（模型没写 [META] 行时，这里就是正文的最后几个字）
                    String rest = filter.flush();
                    if (!rest.isEmpty()) {
                        body.append(rest);
                        tail.add(AssistantStreamEvent.delta(rest));
                    }

                    ChatStreamMeta m = metaHolder.get();
                    String raw = filter.metaLine() == null
                            ? body.toString()
                            : body + "\n" + filter.metaLine();

                    // 与非流式走同一个收尾：置信度解析、引用越界校验、降级提示、落库
                    RagAnswer answer = buildAnswer(p, raw,
                            m == null ? "none" : m.modelId(),
                            m == null ? "none" : m.routedModelId(),
                            m != null && m.degraded(),
                            m == null ? List.of() : m.attempts(),
                            m == null ? "流式调用未完成" : m.routeReason(),
                            System.currentTimeMillis() - start);
                    tail.add(AssistantStreamEvent.done(answer));
                    return Flux.fromIterable(tail);
                }))
                .onErrorResume(ex -> {
                    // 与同步路径一致：模型全挂时给一条能照着做的提示，而不是让前端白屏
                    log.error("[Assistant] 流式调用失败：{}", ex.getMessage(), ex);
                    RagAnswer answer = modelDownAnswer(p, ex, System.currentTimeMillis() - start);
                    return Flux.just(AssistantStreamEvent.done(answer));
                });

        return Flux.concat(Flux.just(AssistantStreamEvent.meta(metaPayload(p))), deltas);
    }

    // ================================================================== 前置阶段（两条路径共用）

    /**
     * 完成"调用模型之前"的全部判断与准备：参数校验 → 会话 → 意图 → 澄清 → 数据域裁决 → 检索 → Prompt。
     *
     * <p>把这一段独立出来的意义在于：<b>拒答与检索是安全边界</b>，
     * 它们绝不能在流式与非流式两条路径里各写一遍。
     */
    private Prepared prepare(AskRequest request, long start) {
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        // 【为什么判空】空问题在后续环节不会"自然失败"：意图会兜底成 GENERAL_QA、
        // 检索返回空、Prompt 照样能拼出来，最后白白调用一次模型。
        // 在这里一行拦掉，等于省一次 LLM 费用 + 一条难以解释的脏日志。
        if (question.isEmpty()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String tenantId = request.getTenantId();
        // 【为什么是"或"】请求级授权（前端显式传 true）与全局默认值取并集：
        // 前者让前端能按会话临时放开；后者只给演示/内网环境用（生产必须为 false）。
        boolean allowPatient = Boolean.TRUE.equals(request.getAllowPatientDomain())
                || properties.getDomain().isAllowPatientByDefault();

        // ① 载入会话（Redis 挂了也会退化成"无记忆"，不影响本次回答）
        ConversationSession session = conversationStore.loadOrCreate(request.getSessionId(), tenantId, allowPatient);

        // ② 意图判定（含追问继承）
        IntentSignal intent = intentClassifier.classify(question, session.getRecentIntents(),
                properties.getRouting().getReasoningLengthThreshold());
        List<String> notes = new ArrayList<>();
        notes.add("意图：" + intent.toString());

        // ③ 澄清分支：问题过于笼统且已有上文时，先问清楚再答，避免给错剂量
        // 【为什么先问不先答】澄清的成本 = 多一次往返（几百毫秒 + 一次便宜调用）；
        // 答错的成本 = 给了一个不该给的剂量/途径。两者量级差太大，所以宁可"显得笨"。
        // 触发条件刻意收得很紧（通用问答 + 有上文 + ≤8 字），否则医生会觉得助手很烦。
        if (shouldClarify(intent, question, session)) {
            notes.add("命中澄清分支：输入过短且无法定位意图，先追问关键信息");
            String systemPrompt = promptService.buildSystemPrompt(
                    "澄清追问",
                    "这一轮不给任何处置建议，只提出最关键的澄清问题。",
                    "（澄清阶段不注入资料）",
                    "未检索（澄清阶段）",
                    AnswerMetaParser.DISCLAIMER);
            String userPrompt = promptService.buildClarifyPrompt(session.getLastQuestion(), question);
            return new Prepared(session, intent, notes, Stage.CLARIFY, question, false, null,
                    null, systemPrompt, userPrompt, null);
        }

        // ④ 数据域判定
        boolean requiresPatient = intent.getIntent().isRequiresPatientDomain();
        // 【为什么三个条件都要】会话允许访问 + 带了 tenantId + tenantId 非空白，缺一即无权限。
        // 少判任何一个都会出现"授权为空 → 过滤条件也为空 → 查到所有租户"的经典漏洞。
        boolean patientAuthorized = session.isAllowPatientDomain() && tenantId != null && !tenantId.isBlank();

        // 【为什么这里直接 return】fail-closed 的核心：授权信息不完整时，
        // 正确动作是"当作没权限"，而不是"当作没限制"（后者就是 fail-open）。
        // 注意 return 之前一次 retriever 都没调 —— 连一条患者数据都不取出来，
        // 也就不存在"取出来了却被模型说漏嘴"的可能。
        if (requiresPatient && !patientAuthorized) {
            String reason = "本次提问涉及具体患者资料，但当前会话未获得患者隐私数据域的访问授权"
                    + (tenantId == null || tenantId.isBlank() ? "（未提供 tenantId）" : "")
                    + "，因此不予作答。请在就医机构内部系统完成授权后重试，或改写为不含患者个体信息的问题。";
            notes.add("数据域判定：患者域未授权，直接拒答（fail-closed）");
            log.warn("[Assistant] 拒答（患者域未授权）：session={} tenant={} intent={}",
                    session.getSessionId(), tenantId, intent.getIntent());
            RagAnswer refusal = finishRefusal(session, question, intent, reason,
                    KnowledgeDomain.PATIENT.getLabel() + "（未授权，未检索）", notes, start);
            return new Prepared(session, intent, notes, Stage.REFUSED, question, requiresPatient,
                    null, null, null, question, refusal);
        }

        Set<KnowledgeDomain> requestDomains = new LinkedHashSet<>();
        requestDomains.add(KnowledgeDomain.PUBLIC);
        // 【为什么只"声明域"、不在这里查】Service 只回答"本轮允许查哪些域"，
        // 至于"这个租户到底能不能看"由 DomainAwareRetriever 统一判定。
        // 授权逻辑只有一处实现，就不会出现 Service 和 Retriever 各判一套、结论还不一致。
        if (requiresPatient) {
            requestDomains.add(KnowledgeDomain.PATIENT);
        }

        // ④b 识别问题里显式点名的患者标识，用于患者维度收敛。
        //     这一步解决的是"拿 A 机构授权去问 B 机构患者"的场景：收敛后必然查空，
        //     于是走 ⑥ 的拒答分支，拒答理由确定，不依赖模型自己判断"资料里没这个人"。
        String scopedPatientId = requiresPatient ? patientIdExtractor.extractSingle(question) : null;

        // ⑤ 数据域感知检索（含越域复核）
        RetrievalResult retrieval = retriever.retrieve(question, requestDomains, tenantId, scopedPatientId);
        notes.addAll(retrieval.getNotes());
        notes.add("检索到 " + retrieval.getSnippets().size() + " 条资料，覆盖数据域：" + retrieval.getSearchedDomains());

        // ⑥ 患者数据缺失 → 拒答（理由见类注释）
        // 【为什么查空也拒答】患者域已授权、却没查到这个人的资料时，如果继续交给模型，
        // 它会拿通用指南顶上；而医生看到一段"有依据"的建议时，很难分辨这是
        // 针对该患者的结论还是泛泛而谈。宁可明确说"没有资料"，也不给一个像答案的答案。
        if (requiresPatient && retrieval.getSnippets().stream()
                .noneMatch(s -> s.getDomain() == KnowledgeDomain.PATIENT)) {
            String reason = scopedPatientId == null
                    ? "在本院患者数据域内未找到与该患者相关的资料，无法针对该患者作答。"
                    + "请确认该患者的病历/检验数据已同步至知识库，或改问不涉及具体患者的通用问题。"
                    : "在本机构（tenantId=" + tenantId + "）的患者数据域内查不到患者 " + scopedPatientId
                    + " 的任何资料，因此不予作答。若该患者属于其他机构，按数据域隔离策略本系统不会跨机构检索；"
                    + "若确有此人，请确认其病历/检验数据已同步至本机构知识库，或核对患者编号是否填写有误。";
            notes.add("数据域判定：患者域已授权但无该患者资料，拒答（点名患者=" + scopedPatientId + "）");
            log.warn("[Assistant] 拒答（患者域内无该患者资料）：session={} tenant={} patient={} intent={}",
                    session.getSessionId(), tenantId, scopedPatientId, intent.getIntent());
            RagAnswer refusal = finishRefusal(session, question, intent, reason, retrieval.describeScope(), notes, start);
            return new Prepared(session, intent, notes, Stage.REFUSED, question, requiresPatient,
                    null, retrieval, null, question, refusal);
        }

        // ⑦ 组装 Prompt
        String systemPrompt = promptService.buildSystemPrompt(
                intent.getIntent().getLabel(),
                intentHint(intent.getIntent(), tenantId, requiresPatient),
                retrieval.renderContextBlock(properties.getRag().getMaxSnippetChars()),
                retrieval.describeScope(),
                AnswerMetaParser.DISCLAIMER);

        return new Prepared(session, intent, notes, Stage.CHAT, question, requiresPatient,
                request.getModel(), retrieval, systemPrompt, question, null);
    }

    /** 组装给门面的调用入参（两条路径共用）。 */
    private ChatRequest chatRequestOf(Prepared p) {
        return ChatRequest.builder()
                .sessionId(p.session().getSessionId())
                .systemPrompt(p.systemPrompt())
                .userMessage(p.userMessage())
                .history(conversationStore.historyMessages(p.session().getSessionId()))
                .explicitModelId(p.explicitModelId())
                .intent(p.intent())
                .build();
    }

    // ================================================================== 收尾阶段（两条路径共用）

    /**
     * 把模型返回的原始文本变成结构化回答，并把本轮记进会话。
     *
     * <p>两条路径都走这里，所以"引用越界告警""降级提示""免责声明补齐"的行为完全一致。
     */
    private RagAnswer buildAnswer(Prepared p, String raw, String modelId, String routedModelId,
                                  boolean degraded, List<ModelAttempt> attempts,
                                  String routeReason, long costMs) {
        boolean clarify = p.stage() == Stage.CLARIFY;

        // ⑨ 结构化：剥掉 [META] 行、解析置信度、校验引用编号、补齐免责声明
        AnswerMetaParser.Parsed parsed = AnswerMetaParser.parse(raw);
        String body = clarify ? parsed.body() : AnswerMetaParser.ensureDisclaimer(parsed.body());

        List<String> notes = p.notes();
        List<RetrievedSnippet> citations = clarify ? new ArrayList<>() : p.retrieval().getSnippets();

        if (!clarify) {
            for (Integer declared : parsed.sources()) {
                if (declared < 1 || declared > citations.size()) {
                    // 模型引用了一个不存在的编号 —— 这是幻觉的明确信号，必须让使用者看到
                    notes.add("⚠️ 模型引用了不存在的资料编号 [" + declared + "]，该引用不可信（系统已保留原始召回清单）");
                    log.warn("[Assistant] 模型引用了越界编号 [{}]，实际召回 {} 条，session={}",
                            declared, citations.size(), p.session().getSessionId());
                }
            }
            if (degraded) {
                notes.add("⚠️ 已发生主备降级：路由模型 " + routedModelId + " 不可用，实际由 " + modelId + " 回答");
            }
        }

        // ⑩ 落库会话
        conversationStore.appendTurn(p.session(), p.question(), p.intent().getIntent(), body, modelId, degraded);

        return RagAnswer.builder()
                .sessionId(p.session().getSessionId())
                .question(p.question())
                .answer(body)
                .confidence(clarify ? "无" : parsed.confidence())
                .basis(clarify ? "澄清" : parsed.basis())
                .declaredSources(parsed.sources())
                .citations(citations)
                .disclaimer(AnswerMetaParser.DISCLAIMER)
                .intentLabel(clarify ? "澄清追问" : p.intent().getIntent().getLabel())
                .routeReason(routeReason)
                .modelId(modelId)
                .routedModelId(routedModelId)
                .degraded(degraded)
                .attempts(attempts)
                .costMs(costMs)
                .domainScope(clarify ? "未检索（澄清阶段）" : p.retrieval().describeScope())
                .refused(false)
                .notes(notes)
                .build();
    }

    /** 模型全部不可用时的兜底回答（两条路径共用）。 */
    private RagAnswer modelDownAnswer(Prepared p, Throwable ex, long costMs) {
        boolean clarify = p.stage() == Stage.CLARIFY;
        List<ModelAttempt> attempts = ex instanceof LlmInvocationException lie ? lie.getAttempts() : List.of();
        String reason = clarify
                ? "需要补充信息才能安全作答，但模型服务当前不可用，未能生成追问。"
                + "请补充患者年龄、体重、症状持续时间、基础疾病与正在使用的药物后重试。"
                : "模型服务暂时不可用，本轮未能生成回答。" + ex.getMessage()
                + "。建议稍后重试，或改用其它已配置的模型（可在 /api/assistant/models 查看可用模型）。";
        conversationStore.appendTurn(p.session(), p.question(), p.intent().getIntent(), reason, "none", false);
        return RagAnswer.builder()
                .sessionId(p.session().getSessionId())
                .question(p.question())
                .answer(reason)
                .refused(true).refuseReason("模型服务不可用")
                .confidence("无").basis("无")
                .declaredSources(new ArrayList<>()).citations(new ArrayList<>())
                .disclaimer(AnswerMetaParser.DISCLAIMER)
                .intentLabel(clarify ? "澄清追问" : p.intent().getIntent().getLabel())
                .routeReason("调用失败")
                .modelId("none").routedModelId("none")
                .attempts(attempts)
                .domainScope(clarify ? "未检索（澄清阶段）" : p.retrieval().describeScope())
                .notes(p.notes())
                .costMs(costMs)
                .build();
    }

    /** 流式接口的 meta 事件负载：调模型之前就能确定的信息。 */
    private Map<String, Object> metaPayload(Prepared p) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", p.session().getSessionId());
        meta.put("intentLabel", describeIntentFor(p));
        meta.put("domainScope", describeScopeFor(p));
        meta.put("citations", p.retrieval() == null ? List.of() : p.retrieval().getSnippets());
        meta.put("notes", new ArrayList<>(p.notes()));
        return meta;
    }

    /**
     * meta 事件里的"本次到底查了哪些数据域"。
     *
     * <p>三种阶段的实情各不相同，不能一律写成"未检索（澄清阶段）"：
     * 拒答同样没有检索，但原因是<b>授权不足</b>而不是<b>问题太笼统</b>，
     * 写成澄清阶段会让排障的人往错误方向查。
     *
     * <p>拒答分支直接复用 {@link RagAnswer#getDomainScope()}，
     * 这样 meta（调模型前下发）与 done（收尾产出）两处的口径<b>永远一致</b>，
     * 前端也不会看到"meta 说澄清、done 说未授权"这种自相矛盾。
     */
    private String describeScopeFor(Prepared p) {
        if (p.retrieval() != null) {
            return p.retrieval().describeScope();
        }
        if (p.stage() == Stage.REFUSED && p.refusal() != null) {
            return p.refusal().getDomainScope();
        }
        return "未检索（澄清阶段）";
    }

    /** meta 事件里的意图标签。 */
    private String describeIntentFor(Prepared p) {
        if (p.stage() == Stage.REFUSED && p.refusal() != null) {
            return p.refusal().getIntentLabel();
        }
        return p.stage() == Stage.CLARIFY ? "澄清追问" : p.intent().getIntent().getLabel();
    }

    // ================================================================== 澄清分支

    /**
     * 信息不足时先澄清。
     *
     * <p>触发条件刻意收得很紧：<b>必须是通用问答、必须已经有上文、且本轮输入不超过 8 个字</b>。
     * 收紧的理由是"澄清"本身有成本（多一轮往返），如果动不动就澄清，
     * 医生会觉得这个助手很笨。只有那种"完全无法定位意图"的超短输入才值得追问。
     */
    private boolean shouldClarify(IntentSignal intent, String question, ConversationSession session) {
        // 【为什么只对通用问答澄清】急诊、用药、患者病历这几类，哪怕输入很短也必须给处置或拒答；
        // 在抢救场景里"先追问一句"可能就是在耽误时间。只有通用问答允许先问清楚。
        if (intent.getIntent() != QuestionIntent.GENERAL_QA) {
            return false;
        }
        // 【为什么必须有上文】没有上一轮时，"追问"等于把问题原样退回去（"你问的是啥？"），
        // 体验极差且毫无信息增量。有上文才可能顺着上一句话问出关键信息。
        if (session.getLastQuestion() == null) {
            return false;
        }
        // 【为什么是 8 个字】超过 8 字通常已经能定位意图了（"这个病人血压高怎么办"），
        // 再追问就是打扰。这个阈值是"够短才问"的经验值，不是语义判断。
        return question.trim().length() <= 8;
    }

    // ================================================================== 工具方法

    /** 统一构造拒答并记录会话。 */
    private RagAnswer finishRefusal(ConversationSession session, String question, IntentSignal intent,
                                    String reason, String domainScope, List<String> notes, long start) {
        conversationStore.appendTurn(session, question, intent.getIntent(), reason, "none", false);
        return RagAnswer.builder()
                .sessionId(session.getSessionId()).question(question)
                .answer(reason)
                .refused(true).refuseReason(reason)
                .confidence("无").basis("无")
                .declaredSources(new ArrayList<>()).citations(new ArrayList<>())
                .disclaimer(AnswerMetaParser.DISCLAIMER)
                .intentLabel(intent.getIntent().getLabel())
                .routeReason("拒答（数据域约束）")
                .modelId("none").routedModelId("none")
                .domainScope(domainScope)
                .notes(notes)
                .costMs(System.currentTimeMillis() - start)
                .build();
    }

    /** 针对不同意图给模型的额外提示。 */
    private String intentHint(QuestionIntent intent, String tenantId, boolean requiresPatient) {
        String base = switch (intent) {
            case EMERGENCY_TRIAGE -> "这是分诊/危重判断问题。先给分级结论和立即处置动作，再补依据；"
                    + "涉及抢救优先级时明确写出判断阈值。";
            case DRUG_QUERY -> "这是用药问题。剂量、途径、频次必须与资料完全一致；"
                    + "资料没写具体数值就直接说不确定，不要用「常规剂量」这种模糊说法蒙过去。";
            case GUIDELINE_QUERY -> "这是指南条文查询。尽量贴近原文表述，标注资料编号，不要自行改写推荐强度。";
            case PATIENT_RECORD_QUERY -> "这是针对具体患者的问题。只依据下面提供的该患者资料作答，"
                    + "不要把资料里的结论推广到其他患者。";
            case COMPLEX_REASONING -> "这是复杂推理问题。请显式分步推理，写清每一步的依据；"
                    + "存在不确定性的地方要标出来，不要把推测写成结论。";
            case GENERAL_QA -> "通用医学问答，保持简洁，直接回答。";
            case CHITCHAT -> "这不是临床问题。用一两句话简短回应，并引导用户提出临床相关的问题。";
        };
        if (requiresPatient && tenantId != null && !tenantId.isBlank()) {
            base += "（本轮患者资料仅限本机构，禁止引用或推断其他机构的数据）";
        }
        return base;
    }

    // ================================================================== 内部类型

    /** 本轮要走的路径。 */
    private enum Stage {
        /** 正常问答（含数据域检索）。 */
        CHAT,
        /** 信息不足，先追问。 */
        CLARIFY,
        /** 直接拒答（不检索、不调模型）。 */
        REFUSED
    }

    /** 前置阶段的产出：要么已经判定拒答，要么带着素材进入模型调用。 */
    private record Prepared(ConversationSession session, IntentSignal intent, List<String> notes,
                            Stage stage, String question, boolean requiresPatient,
                            String explicitModelId, RetrievalResult retrieval,
                            String systemPrompt, String userMessage, RagAnswer refusal) {
    }

    /**
     * 流式输出的 {@code [META]} 行剥离器。
     *
     * <p>为什么要它：模型被要求把元信息写在正文末尾（{@code [META] confidence=高; basis=…; sources=1,2}），
     * 非流式时一句正则就能剥掉；但流式下这一行<b>会跨多个 chunk 到达</b>，
     * 逐块做正则必然漏判，医生就会在正文里看到 {@code [META] confidence=…} 这种内部标记。
     *
     * <p>做法：留一个"滚动缓冲区"，永远扣住尾部 {@code [META].length()-1} 个字符不发，
     * 因为那有可能是被切断的标记开头（例如本块以 {@code "[ME"} 结尾）。
     * 一旦在缓冲区里认出完整的 {@code [META]}，就把标记之前的内容一次性发出，
     * 之后的所有 chunk 都转入元信息缓冲，不再作为正文。
     *
     * <p>匹配不区分大小写（模型偶尔会写小写），但判断位置用的是大写化后的下标。
     */
    private static final class MetaStreamFilter {

        private static final String TAG = "[META]";

        /** 尚未确定安全的尾部。 */
        private final StringBuilder pending = new StringBuilder();
        /** 收集到的元信息行（从 [META] 开始）。 */
        private final StringBuilder meta = new StringBuilder();
        private boolean inMeta;

        /** 送入一段增量，返回可以立刻发给用户的正文（可能为空串）。 */
        String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }
            if (inMeta) {
                meta.append(chunk);
                return "";
            }
            pending.append(chunk);

            int idx = pending.toString().toUpperCase(Locale.ROOT).indexOf(TAG);
            if (idx >= 0) {
                String out = pending.substring(0, idx);
                meta.append(pending.substring(idx));
                pending.setLength(0);
                inMeta = true;
                return out;
            }

            // 没出现标记：只保留可能是"标记前缀"的尾部
            int keep = TAG.length() - 1;
            if (pending.length() <= keep) {
                return "";
            }
            String out = pending.substring(0, pending.length() - keep);
            pending.delete(0, pending.length() - keep);
            return out;
        }

        /** 流结束：把缓冲区剩余内容交还（模型没写元信息行时，这里就是正文的最后几个字）。 */
        String flush() {
            if (inMeta) {
                return "";
            }
            String out = pending.toString();
            pending.setLength(0);
            return out;
        }

        /** 收集到的元信息行（没有则返回 null）。 */
        String metaLine() {
            return meta.length() == 0 ? null : meta.toString();
        }
    }
}
