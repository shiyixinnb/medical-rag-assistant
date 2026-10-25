package com.syx.demo.llm.guard;

import com.syx.demo.llm.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 自研的滑动窗口熔断器 —— Sentinel 不可用时的兜底实现。
 *
 * <p>行为对齐 Sentinel 的"异常比例"降级模式：
 * 窗口内样本数达到 {@code minRequestAmount} 且坏调用比例超过 {@code failureRateThreshold} 时打开熔断；
 * 静默 {@code timeWindowSeconds} 秒后进入半开，放<b>一个</b>探测请求过去，
 * 探测成功就恢复正常，失败就继续熔断。
 *
 * <p><b>这里做了一个跟 Sentinel 不一样的取舍</b>：慢调用也算"坏调用"。
 * 原因是急诊问答场景里"12 秒才返回"和"报错"对用户的伤害是一样的 ——
 * 医生不会因为接口没报错就觉得可以等。默认 {@code slowCallRtMs=15s} 以上视为坏调用，
 * 设为 0 可以关掉这个行为。
 *
 * <p><b>【阅读导航】</b>状态只有三样：当前是否打开、打开的时间、窗口内的调用记录。
 * 读的时候盯住两条主线：
 * <ul>
 *   <li>{@link #execute} —— 同步调用：进入时判状态 → 执行 → 记账 → 可能打开熔断；</li>
 *   <li>{@link #guardStream} —— 流式调用：用 {@code asyncEntry} 申请许可（回调在别的线程，
 *       线程本地的 {@code entry} 不管用），并且 {@code SignalType.CANCEL} 只释放半开位、
 *       不记失败 —— 否则用户点几次"停止生成"就会被误判成模型故障。</li>
 * </ul>
 */
public class SimpleSlidingWindowGuard implements ModelCallGuard {

    private static final Logger log = LoggerFactory.getLogger(SimpleSlidingWindowGuard.class);

    private final LlmProperties.Breaker cfg;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public SimpleSlidingWindowGuard(LlmProperties.Breaker cfg) {
        this.cfg = cfg;
    }

    @Override
    public String backend() {
        return "simple";
    }

    @Override
    public <T> T execute(String resource, Supplier<T> action) throws ModelBlockedException {
        Window w = windows.computeIfAbsent(resource, Window::new);
        if (!w.tryAcquire(System.currentTimeMillis())) {
            throw new ModelBlockedException(resource,
                    "模型 " + resource + " 处于熔断打开状态（自研滑动窗口），本次调用被拒并直接降级", null);
        }
        long start = System.currentTimeMillis();
        try {
            T result = action.get();
            w.onComplete(System.currentTimeMillis() - start, true, System.currentTimeMillis());
            return result;
        }
        catch (RuntimeException | Error e) {
            w.onComplete(System.currentTimeMillis() - start, false, System.currentTimeMillis());
            throw e;
        }
    }

    @Override
    public Map<String, GuardSnapshot> snapshots() {
        Map<String, GuardSnapshot> result = new LinkedHashMap<>();
        windows.forEach((name, w) -> result.put(name, w.snapshot(name, backend())));
        return result;
    }

    @Override
    public boolean isOpen(String resource) {
        Window w = windows.get(resource);
        return w != null && w.currentState(System.currentTimeMillis()) == State.OPEN;
    }

    /**
     * 流式调用：语义与同步版完全一致，只是"记账"推迟到流终止的那一刻。
     *
     * <p>许可在返回 Flux 之前就申请完毕（同步抛 {@code ModelBlockedException}），
     * 这样调用方能在还没输出任何内容时切换模型。
     *
     * <p>客户端主动断开（CANCEL）<b>不记账</b>：那是用户行为，不是模型故障，
     * 记成坏调用会让正常的中断累积成误熔断。
     */
    @Override
    public <T> Flux<T> guardStream(String resource, Supplier<Flux<T>> action) throws ModelBlockedException {
        Window w = windows.computeIfAbsent(resource, Window::new);
        if (!w.tryAcquire(System.currentTimeMillis())) {
            throw new ModelBlockedException(resource,
                    "模型 " + resource + " 处于熔断打开状态（自研滑动窗口），本次流式调用被拒并直接降级", null);
        }
        long start = System.currentTimeMillis();
        AtomicBoolean settled = new AtomicBoolean(false);
        try {
            return action.get()
                    .doOnComplete(() -> {
                        if (settled.compareAndSet(false, true)) {
                            w.onComplete(System.currentTimeMillis() - start, true, System.currentTimeMillis());
                        }
                    })
                    .doOnError(ex -> {
                        if (settled.compareAndSet(false, true)) {
                            w.onComplete(System.currentTimeMillis() - start, false, System.currentTimeMillis());
                        }
                    })
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL && settled.compareAndSet(false, true)) {
                            w.onAbandon();
                        }
                    });
        }
        catch (RuntimeException | Error e) {
            if (settled.compareAndSet(false, true)) {
                w.onComplete(System.currentTimeMillis() - start, false, System.currentTimeMillis());
            }
            throw e;
        }
    }

    private enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    /** 单个模型（资源）的熔断窗口。所有状态变更都在 synchronized 里做，读取则尽量无锁。 */
    private final class Window {

        private final String resource;
        private final Deque<Sample> samples = new ArrayDeque<>();
        private State state = State.CLOSED;
        private long openedAt;
        /** 半开探测是否已经有一个请求在飞。 */
        private boolean probeInFlight;

        private long totalCalls;
        private long totalFailures;
        private long blocked;
        private volatile long lastBlockedAt;

        private Window(String resource) {
            this.resource = resource;
        }

        /** 尝试获取执行许可。返回 false 表示处于熔断状态。 */
        synchronized boolean tryAcquire(long now) {
            prune(now);
            switch (state) {
                case OPEN -> {
                    if (now - openedAt >= cfg.getTimeWindowSeconds() * 1000L) {
                        state = State.HALF_OPEN;
                        probeInFlight = true;
                        log.info("[LLM-Guard] {} 静默期结束，进入半开状态，放行 1 个探测请求", detail());
                        return true;
                    }
                    blocked++;
                    lastBlockedAt = now;
                    return false;
                }
                case HALF_OPEN -> {
                    if (probeInFlight) {
                        // 探测还没回来，其余请求继续挡住，避免"雪崩式"试探
                        blocked++;
                        lastBlockedAt = now;
                        return false;
                    }
                    probeInFlight = true;
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }

        synchronized void onComplete(long costMs, boolean success, long now) {
            boolean slow = cfg.getSlowCallRtMs() > 0 && costMs >= cfg.getSlowCallRtMs();
            // 慢调用按坏调用处理，理由见类注释
            boolean bad = !success || slow;

            totalCalls++;
            if (bad) {
                totalFailures++;
            }

            if (state == State.HALF_OPEN) {
                probeInFlight = false;
                if (bad) {
                    open(now, "半开探测失败" + (slow ? "（慢调用 " + costMs + "ms）" : ""));
                }
                else {
                    close("半开探测成功");
                }
                return;
            }

            samples.addLast(new Sample(now, bad));
            prune(now);
            evaluate(now);
        }

        /** 流被取消：不记账，只释放半开探测位，避免后续请求被永久挡在门外。 */
        synchronized void onAbandon() {
            if (state == State.HALF_OPEN) {
                probeInFlight = false;
            }
        }

        private void evaluate(long now) {
            if (state != State.CLOSED) {
                return;
            }
            if (samples.size() < cfg.getMinRequestAmount()) {
                return;
            }
            long bad = samples.stream().filter(s -> s.bad).count();
            double rate = (double) bad / samples.size();
            if (rate >= cfg.getFailureRateThreshold()) {
                open(now, String.format("窗口内坏调用比例 %.0f%%（%d/%d）≥ 阈值 %.0f%%",
                        rate * 100, bad, samples.size(), cfg.getFailureRateThreshold() * 100));
            }
        }

        private void open(long now, String reason) {
            state = State.OPEN;
            openedAt = now;
            probeInFlight = false;
            samples.clear();
            log.warn("[LLM-Guard] {} 熔断打开：{}，静默 {}s", detail(), reason, cfg.getTimeWindowSeconds());
        }

        private void close(String reason) {
            state = State.CLOSED;
            openedAt = 0;
            probeInFlight = false;
            samples.clear();
            log.info("[LLM-Guard] {} 熔断恢复：{}", detail(), reason);
        }

        private void prune(long now) {
            long deadline = now - cfg.getStatIntervalMs();
            while (!samples.isEmpty() && samples.peekFirst().at < deadline) {
                samples.pollFirst();
            }
        }

        private String detail() {
            return resource;
        }

        synchronized State currentState(long now) {
            if (state == State.OPEN && now - openedAt >= cfg.getTimeWindowSeconds() * 1000L) {
                return State.HALF_OPEN;
            }
            return state;
        }

        synchronized GuardSnapshot snapshot(String ignoredResource, String backend) {
            long now = System.currentTimeMillis();
            long bad = samples.stream().filter(s -> s.bad).count();
            double rate = samples.isEmpty() ? 0 : (double) bad / samples.size();
            return new GuardSnapshot(resource, backend, currentState(now).name(), totalCalls, totalFailures,
                    blocked, rate, openedAt,
                    "自研滑动窗口；窗口 " + cfg.getStatIntervalMs() + "ms，慢调用阈值 "
                            + (cfg.getSlowCallRtMs() > 0 ? cfg.getSlowCallRtMs() + "ms" : "关闭"));
        }
    }

    /** 一次调用的样本。只留"好/坏"和时间戳，够用于比例计算。 */
    private static final class Sample {
        private final long at;
        private final boolean bad;

        private Sample(long at, boolean bad) {
            this.at = at;
            this.bad = bad;
        }
    }
}
