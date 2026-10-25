package com.syx.demo.llm.guard;

import com.alibaba.csp.sentinel.AsyncEntry;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.syx.demo.llm.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 基于 Sentinel 的熔断实现（生产推荐）。
 *
 * <p>每个模型一个 Sentinel 资源，资源名直接用模型 id。规则在第一次用到该模型时惰性注册，
 * 因此新增模型不需要在代码里登记。
 *
 * <p>规则内容：
 * <ul>
 *   <li><b>异常比例降级</b>：窗口内坏调用比例超阈值就打开 —— 对应"DeepSeek 大面积 5xx/超时"；</li>
 *   <li><b>慢调用降级</b>：RT 超阈值的比例过高也打开 —— 对应"接口没报错但慢到不可用"，
 *       这是纯异常比例规则抓不到的场景。</li>
 * </ul>
 *
 * <p>调用失败必须通过 {@link Tracer#traceEntry} 显式上报，Sentinel 才能统计到；
 * 这是最容易漏的一步，漏了就会出现"明明一直在报错，熔断却永远不打开"。
 */
public class SentinelModelCallGuard implements ModelCallGuard {

    private static final Logger log = LoggerFactory.getLogger(SentinelModelCallGuard.class);

    private final LlmProperties.Breaker cfg;

    /** 已注册规则的资源，避免重复 loadRules（loadRules 是全量替换语义）。 */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    /** 本地计数器：Sentinel 内部状态不对外暴露，这些计数用于运维接口展示。 */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public SentinelModelCallGuard(LlmProperties.Breaker cfg) {
        this.cfg = cfg;
        log.info("[LLM-Guard] 使用 Sentinel 熔断后端：异常比例阈值 {}，最小样本 {}，统计窗口 {}ms，静默 {}s，慢调用阈值 {}ms",
                cfg.getFailureRateThreshold(), cfg.getMinRequestAmount(), cfg.getStatIntervalMs(),
                cfg.getTimeWindowSeconds(), cfg.getSlowCallRtMs());
    }

    @Override
    public String backend() {
        return "sentinel";
    }

    @Override
    public <T> T execute(String resource, Supplier<T> action) throws ModelBlockedException {
        ensureRules(resource);
        Counter counter = counters.computeIfAbsent(resource, k -> new Counter());
        counter.calls.incrementAndGet();

        Entry entry = null;
        try {
            // EntryType.OUT：把模型的调用标记为"出向依赖"，语义上更准确，也便于后续接 Dashboard
            entry = SphU.entry(resource, EntryType.OUT);
            T result = action.get();
            counter.lastSuccessAt = System.currentTimeMillis();
            return result;
        }
        catch (BlockException be) {
            counter.blocked.incrementAndGet();
            throw new ModelBlockedException(resource,
                    "模型 " + resource + " 被 Sentinel 熔断/限流拦截（" + be.getClass().getSimpleName() + "），直接降级",
                    be);
        }
        catch (RuntimeException | Error e) {
            counter.failures.incrementAndGet();
            // ⭐ 必须上报，否则 Sentinel 统计不到异常，比例规则永远不会触发
            if (entry != null) {
                Tracer.traceEntry(e, entry);
            }
            throw e;
        }
        finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    /** 惰性注册：按新出现的资源增量重建全量规则集。 */
    private void ensureRules(String resource) {
        if (!registered.add(resource)) {
            return;
        }
        List<DegradeRule> rules = new ArrayList<>(registered.size() * 2);

        for (String r : registered) {
            // ① 异常比例：坏调用占比过高 → 熔断
            DegradeRule ratio = new DegradeRule(r)
                    .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                    .setCount(cfg.getFailureRateThreshold())
                    .setTimeWindow(cfg.getTimeWindowSeconds())
                    .setMinRequestAmount(cfg.getMinRequestAmount())
                    .setStatIntervalMs(cfg.getStatIntervalMs());
            rules.add(ratio);

            // ② 慢调用比例：RT 超阈值的占比过高 → 熔断（"没报错但慢到不可用"）
            if (cfg.getSlowCallRtMs() > 0) {
                DegradeRule slow = new DegradeRule(r)
                        .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                        .setCount(cfg.getSlowCallRtMs())
                        .setSlowRatioThreshold(cfg.getFailureRateThreshold())
                        .setTimeWindow(cfg.getTimeWindowSeconds())
                        .setMinRequestAmount(cfg.getMinRequestAmount())
                        .setStatIntervalMs(cfg.getStatIntervalMs());
                rules.add(slow);
            }
        }

        DegradeRuleManager.loadRules(rules);
        log.info("[LLM-Guard] 已为资源 {} 注册 Sentinel 降级规则，当前规则总数 {}", resource, rules.size());
    }

    @Override
    public Map<String, GuardSnapshot> snapshots() {
        Map<String, GuardSnapshot> result = new LinkedHashMap<>();
        counters.forEach((resource, c) -> {
            long calls = c.calls.get();
            long failures = c.failures.get();
            long blocked = c.blocked.get();
            double rate = calls == 0 ? 0 : (double) failures / calls;
            // 最近被拦截且此后没有成功调用，基本可以判定熔断还开着
            String state = blocked > 0 && c.lastBlockedAt > c.lastSuccessAt ? "OPEN" : "CLOSED";
            result.put(resource, new GuardSnapshot(resource, backend(), state, calls, failures, blocked, rate,
                    c.lastBlockedAt, "状态由 Sentinel 滑动窗口判定，此处为基于计数器的推断值"));
        });
        return result;
    }

    @Override
    public boolean isOpen(String resource) {
        Counter c = counters.get(resource);
        return c != null && c.blocked.get() > 0 && c.lastBlockedAt > c.lastSuccessAt;
    }

    /**
     * 流式调用：用 {@link SphU#asyncEntry} 申请许可，并在流终止时跨线程释放。
     *
     * <p>为什么不能用普通 {@code SphU.entry}：它的 Entry 挂在 ThreadLocal 的 Context 上，
     * 而流的回调跑在 Reactor / Netty 线程上，跨线程 {@code exit()} 会被 Sentinel 判定为
     * "entry not belong to current context"，统计随之失真。{@code AsyncEntry} 是 Sentinel
     * 官方为异步场景提供的入口，允许在任意线程 exit，并且不需要 ThreadLocal Context。
     *
     * <p>许可申请是<b>同步完成</b>的（本方法返回前就能抛 {@code ModelBlockedException}），
     * 这样 ChatFacade 才能在"还没给用户吐出任何内容"之前安全地切到下一个模型。
     */
    @Override
    public <T> Flux<T> guardStream(String resource, Supplier<Flux<T>> action) throws ModelBlockedException {
        ensureRules(resource);
        Counter counter = counters.computeIfAbsent(resource, k -> new Counter());
        counter.calls.incrementAndGet();

        AsyncEntry entry;
        try {
            entry = SphU.asyncEntry(resource, EntryType.OUT);
        }
        catch (BlockException be) {
            counter.blocked.incrementAndGet();
            throw new ModelBlockedException(resource,
                    "模型 " + resource + " 被 Sentinel 熔断/限流拦截（" + be.getClass().getSimpleName() + "），直接降级",
                    be);
        }

        // exit 必须恰好一次。流的终止信号（complete / error / cancel）只会有一个，
        // 所以 doFinally 是天然的"配平点"。
        AtomicBoolean exited = new AtomicBoolean(false);
        try {
            return action.get()
                    .doOnComplete(() -> counter.lastSuccessAt = System.currentTimeMillis())
                    .doOnError(ex -> {
                        counter.failures.incrementAndGet();
                        // ⭐ 与 execute 同理：不上报 Sentinel 就统计不到，异常比例规则永远不触发
                        Tracer.traceEntry(ex, entry);
                    })
                    .doFinally(signal -> {
                        if (exited.compareAndSet(false, true)) {
                            // 客户端中途断开（CANCEL）也会走到这里：不记为失败（不是模型的错），但必须释放
                            entry.exit();
                        }
                    });
        }
        catch (RuntimeException | Error e) {
            // 构造 Flux 阶段就抛了（极少见）：必须手动释放，否则异步 Entry 会泄漏
            counter.failures.incrementAndGet();
            Tracer.traceEntry(e, entry);
            if (exited.compareAndSet(false, true)) {
                entry.exit();
            }
            throw e;
        }
    }

    /** 轻量计数器，只为可观测性服务。 */
    private static final class Counter {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong blocked = new AtomicLong();
        private volatile long lastBlockedAt;
        private volatile long lastSuccessAt;
    }
}
