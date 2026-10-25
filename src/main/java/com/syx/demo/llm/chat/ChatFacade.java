package com.syx.demo.llm.chat;

import com.syx.demo.llm.config.LlmProperties;
import com.syx.demo.llm.guard.ModelBlockedException;
import com.syx.demo.llm.guard.ModelCallGuard;
import com.syx.demo.llm.model.ModelDefinition;
import com.syx.demo.llm.model.ModelRegistry;
import com.syx.demo.llm.route.RoutingDecision;
import com.syx.demo.llm.route.RoutingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 调用门面 —— 这是整个接入层唯一的出口，所有模型调用都必须经过它。
 *
 * <p>一次调用的完整链路：
 *
 * <pre>
 *   意图判定 → 能力路由（选出主模型 + 展开降级链）
 *        ↓
 *   对候选链里的每个模型：
 *        熔断检查（打开则跳过）
 *             ↓
 *        尝试 1 → 失败且可重试 → 退避 → 尝试 2 → …
 *             ↓
 *        仍失败 → 换下一个候选模型
 *        ↓
 *   全部失败 → 抛 LlmInvocationException（带完整尝试记录）
 * </pre>
 *
 * <p><b>三个刻意的设计决定</b>：
 *
 * <ol>
 *   <li><b>熔断打开时不重试，直接换模型。</b>熔断的前提是下游已经过载，这时候重试等于雪上加霜。</li>
 *   <li><b>只对"可能自愈"的错误重试。</b>429/5xx/超时值得再试一次；401/403/参数错误重试一万次也一样，
 *       直接换模型才是正确动作。</li>
 *   <li><b>未知异常默认不重试。</b>急诊场景里"快速切到备胎"比"在主模型上多等几秒"更有价值 ——
 *       医生等 10 秒拿不到答案，跟拿不到答案没区别。</li>
 * </ol>
 *
 * <p><b>【阅读导航】抓住"四层包装"，这个类就不会看乱：</b>
 * <pre>
 *   public call(request)              第 1 层：在候选链上换模型（降级）
 *     └ invokeWithRetry(model, ...)   第 2 层：同一个模型上重试（只对 429/5xx/超时）
 *        └ guard.execute(id, () -> ...)  第 3 层：熔断的进入 / 上报 / 退出
 *           └ invokeOnce(model, ...)     第 4 层：真正发 HTTP，一行容错都没有
 * </pre>
 * 每层只负责一件事，所以出问题时能精确定位：是重试策略不对、熔断误开，还是模型真的挂了。
 *
 * <p>流式路径（{@link #stream}）的结构一样，只是第 1 层从 {@code for} 循环换成了
 * {@link #streamChain} 的递归 —— 因为异步回调里没法 {@code continue}。
 */
@Component
public class ChatFacade {

    private static final Logger log = LoggerFactory.getLogger(ChatFacade.class);

    private final ModelRegistry registry;
    private final RoutingStrategy routingStrategy;
    private final ModelCallGuard guard;
    private final LlmProperties properties;

    public ChatFacade(ModelRegistry registry, RoutingStrategy routingStrategy,
                      ModelCallGuard guard, LlmProperties properties) {
        this.registry = registry;
        this.routingStrategy = routingStrategy;
        this.guard = guard;
        this.properties = properties;
    }

    /** 路由 + 调用。返回结果里带着完整的决策与降级痕迹。 */
    public ChatResult call(ChatRequest request) {
        RoutingDecision decision = decide(request);
        List<ModelDefinition> chain = decision.usableChain();
        List<ModelAttempt> attempts = new ArrayList<>();
        long startAll = System.currentTimeMillis();

        for (int idx = 0; idx < chain.size(); idx++) {
            ModelDefinition model = chain.get(idx);

            // ① 熔断打开：直接跳过，不做任何重试
            if (guard.isOpen(model.getId())) {
                attempts.add(ModelAttempt.blocked(model.getId()));
                log.warn("[LLM] {} 处于熔断状态，跳过", model.getId());
                continue;
            }

            // ② 在该模型上按重试策略尝试
            String content = invokeWithRetry(model, request, attempts);
            if (content != null) {
                boolean degraded = !model.getId().equals(decision.getPrimary().getId());
                long cost = System.currentTimeMillis() - startAll;
                if (degraded) {
                    log.warn("[LLM] 已降级：主模型 {} 不可用，改由 {} 回答（耗时 {}ms，尝试链 {}）",
                            decision.getPrimary().getId(), model.getId(), cost, attempts.size());
                }
                return new ChatResult(content, model.getId(), decision.getPrimary().getId(), degraded,
                        attempts, decision.getReason(),
                        decision.getIntent() == null ? "-" : decision.getIntent().getIntent().getLabel(),
                        cost);
            }
        }

        // ③ 全军覆没
        String msg = "所有候选模型均调用失败，候选链=" + decision.describeChain();
        log.error("[LLM] {}；尝试记录：{}", msg, attempts);
        throw new LlmInvocationException(msg, attempts, lastCause(attempts));
    }

    /**
     * 路由 + <b>流式</b>调用。返回的事件流里，前若干个是正文增量，最后一个是结束元信息。
     *
     * <p>与 {@link #call} 相比，流式多了一个无法回避的约束：
     * <b>一旦有内容发给用户了，就不能再换模型</b>——否则用户会看到两条答案首尾拼接在一起。
     * 所以降级只在"一个字都还没发出去之前"发生，这也正是把许可申请
     * （{@link ModelCallGuard#guardStream}）做成同步动作的原因。
     */
    public Flux<ChatStreamEvent> stream(ChatRequest request) {
        RoutingDecision decision = decide(request);
        List<ModelAttempt> attempts = new ArrayList<>();
        long startAll = System.currentTimeMillis();
        return streamChain(decision, decision.usableChain(), 0, request, attempts, startAll);
    }

    /**
     * 沿候选链尝试流式调用（递归下降，失败则换下一个模型）。
     *
     * <p>用 {@code Flux.defer} 包住每一层：这样"申请许可 + 构造上游请求"发生在<b>订阅时</b>，
     * 而不是在拼装 Flux 的时候——否则第一个模型会被无意义地预热一次。
     */
    private Flux<ChatStreamEvent> streamChain(RoutingDecision decision, List<ModelDefinition> chain,
                                              int idx, ChatRequest request,
                                              List<ModelAttempt> attempts, long startAll) {
        if (idx >= chain.size()) {
            String msg = "所有候选模型均调用失败（流式），候选链=" + decision.describeChain();
            log.error("[LLM] {}；尝试记录：{}", msg, attempts);
            return Flux.error(new LlmInvocationException(msg, attempts, lastCause(attempts)));
        }

        ModelDefinition model = chain.get(idx);
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            long start = System.currentTimeMillis();

            Flux<String> content;
            try {
                content = guard.guardStream(model.getId(), () -> buildStream(model, request));
            }
            catch (ModelBlockedException blocked) {
                // 熔断是"同步可判定"的，所以这里可以在还没输出任何内容时直接换模型
                attempts.add(ModelAttempt.blocked(model.getId()));
                log.warn("[LLM] {} 处于熔断状态，跳过（流式）：{}", model.getId(), blocked.getMessage());
                return streamChain(decision, chain, idx + 1, request, attempts, startAll);
            }

            return content
                    .map(ChatStreamEvent::delta)
                    .doOnNext(e -> emitted.set(true))
                    .concatWith(Flux.defer(() -> {
                        long cost = System.currentTimeMillis() - startAll;
                        boolean degraded = !model.getId().equals(decision.getPrimary().getId());
                        attempts.add(ModelAttempt.success(model.getId(), 1, System.currentTimeMillis() - start));
                        if (degraded) {
                            log.warn("[LLM] 已降级（流式）：主模型 {} 不可用，改由 {} 回答（耗时 {}ms）",
                                    decision.getPrimary().getId(), model.getId(), cost);
                        }
                        return Flux.just(ChatStreamEvent.finished(new ChatStreamMeta(
                                model.getId(), decision.getPrimary().getId(), degraded,
                                attempts, decision.getReason(), cost)));
                    }))
                    .onErrorResume(ex -> {
                        attempts.add(ModelAttempt.failure(model.getId(), 1, System.currentTimeMillis() - start, ex));
                        if (emitted.get()) {
                            // 已经吐过内容：不能换模型重答（会出现两段拼接的答案），只能让这一轮失败
                            log.warn("[LLM] {} 流式中途断开，因已输出内容不再降级：{}", model.getId(), ex.getMessage());
                            return Flux.error(ex);
                        }
                        if (!isRetryable(ex)) {
                            log.warn("[LLM] {} 遇到不可重试错误（{}），直接降级（流式）：{}",
                                    model.getId(), ex.getClass().getSimpleName(), ex.getMessage());
                        }
                        return streamChain(decision, chain, idx + 1, request, attempts, startAll);
                    });
        });
    }

    /** 构造真正的流式请求。与 {@link #invokeOnce} 只差最后一步：{@code stream()} 而不是 {@code call()}。 */
    private Flux<String> buildStream(ModelDefinition model, ChatRequest request) {
        ChatClient.ChatClientRequestSpec spec = model.getChatClient()
                .prompt()
                .system(request.getSystemPrompt());

        if (!request.getHistory().isEmpty()) {
            spec = spec.messages(request.getHistory());
        }
        return spec.user(request.getUserMessage()).stream().content();
    }

    // ------------------------------------------------------------------ 内部实现

    /** 决定用哪个模型：显式指定优先，否则走路由策略。 */
    private RoutingDecision decide(ChatRequest request) {
        String explicit = request.getExplicitModelId();
        if (explicit != null && !explicit.isBlank()) {
            ModelDefinition model = registry.find(explicit).orElse(null);
            if (model != null) {
                return routingStrategy.explicit(model, request.getIntent());
            }
            // 指定了一个没注册的模型：不报错，退回自动路由，但要留下痕迹
            log.warn("[LLM] 指定的模型 {} 未注册，改为自动路由", explicit);
        }
        return routingStrategy.route(request.getIntent(), request.getUserMessage());
    }

    /**
     * 在单个模型上尝试（含重试）。成功返回内容，失败返回 null（并已写入 attempts）。
     */
    private String invokeWithRetry(ModelDefinition model, ChatRequest request, List<ModelAttempt> attempts) {
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                sleepBackoff(attempt);
            }
            long start = System.currentTimeMillis();
            try {
                // 熔断护栏包住真正的调用；空响应也要当失败处理（网关限流时常返回 200 + 空 body）
                String content = guard.execute(model.getId(), () -> invokeOnce(model, request));
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("模型返回内容为空（可能是上游限流或网关异常）");
                }
                attempts.add(ModelAttempt.success(model.getId(), attempt, System.currentTimeMillis() - start));
                return content;
            }
            catch (ModelBlockedException blocked) {
                // 熔断：本次及后续重试都没有意义，交给外层换模型
                attempts.add(ModelAttempt.blocked(model.getId()));
                log.warn("[LLM] {} 调用被熔断拦截：{}", model.getId(), blocked.getMessage());
                return null;
            }
            catch (RuntimeException ex) {
                attempts.add(ModelAttempt.failure(model.getId(), attempt,
                        System.currentTimeMillis() - start, ex));
                if (!isRetryable(ex)) {
                    log.warn("[LLM] {} 遇到不可重试错误（{}），直接降级：{}",
                            model.getId(), ex.getClass().getSimpleName(), ex.getMessage());
                    return null;
                }
                if (attempt == maxAttempts) {
                    log.warn("[LLM] {} 重试 {} 次仍失败，准备降级：{}", model.getId(), maxAttempts, ex.getMessage());
                }
            }
        }
        return null;
    }

    /** 真正的一次模型调用。这里只做"发请求、取文本"，不掺任何容错逻辑。 */
    private String invokeOnce(ModelDefinition model, ChatRequest request) {
        ChatClient.ChatClientRequestSpec spec = model.getChatClient()
                .prompt()
                .system(request.getSystemPrompt());

        // 历史消息用独立 Message 列表传入（而不是拼进 user 文本），
        // 这样模型能正确区分"上一轮说的"和"这一轮问的"，追问体验才自然
        if (!request.getHistory().isEmpty()) {
            spec = spec.messages(request.getHistory());
        }
        return spec.user(request.getUserMessage()).call().content();
    }

    /** 指数退避；第一次重试前等 backoffMs，之后翻倍。 */
    private void sleepBackoff(int attempt) {
        long delay = properties.getRetry().getBackoffMs() * (1L << Math.max(0, attempt - 2));
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断异常是否值得重试。
     *
     * <p>原则：<b>换一个模型能解决的问题不值得重试，等一等可能自愈的问题才重试。</b>
     * 所以限流(429)、服务端错误(5xx)、超时 → 重试（但外层的降级通常更快见效，所以重试次数默认只有 1~2）；
     * 鉴权失败(401/403)、请求非法(400) → 立刻降级。
     */
    private boolean isRetryable(Throwable ex) {
        if (ex instanceof NonTransientAiException) {
            return false;
        }
        if (ex instanceof TransientAiException || ex instanceof ResourceAccessException
                || ex instanceof SocketTimeoutException) {
            return true;
        }
        // 兜底：从异常消息里认状态码（不同版本的 Spring AI 包装层不一样，消息里一定有原始状态码）
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (containsAny(m, "401", "403", "400", "unauthorized", "invalid_api_key", "invalid api key",
                "insufficient balance", "insufficient_quota", "invalid_request_error")) {
            return false;
        }
        return containsAny(m, "429", "500", "502", "503", "504", "too many requests", "timeout",
                "timed out", "connection reset", "read timed out");
    }

    private boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private Throwable lastCause(List<ModelAttempt> attempts) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            if (attempts.get(i).getErrorMessage() != null) {
                return new IllegalStateException(attempts.get(i).getErrorMessage());
            }
        }
        return null;
    }

    /** 供运维接口展示各模型熔断状态。 */
    public ModelCallGuard getGuard() {
        return guard;
    }
}
