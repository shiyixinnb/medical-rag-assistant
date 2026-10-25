package com.syx.demo.llm.guard;

/**
 * 熔断状态的只读快照。
 *
 * <p>做成不可变对象而不是直接暴露内部 {@code Deque}，是为了让 {@code /api/assistant/health}
 * 这类运维接口可以放心地并发读取，不用去抢内部锁。
 */
public final class GuardSnapshot {

    /** 资源名（= 模型 id）。 */
    private final String resource;
    /** 护栏实现。 */
    private final String backend;
    /** 状态文案：CLOSED / OPEN / HALF_OPEN_UNKNOWN 等。 */
    private final String state;
    /** 统计窗口内的调用次数。 */
    private final long calls;
    /** 失败次数。 */
    private final long failures;
    /** 被熔断拦截的次数。 */
    private final long blocked;
    /** 失败率（0~1）。 */
    private final double failureRate;
    /** 熔断打开的时间戳（毫秒），未打开为 0。 */
    private final long openedAt;
    /** 附加说明（例如 Sentinel 后端由 Sentinel 自身维护滑动窗口）。 */
    private final String note;

    public GuardSnapshot(String resource, String backend, String state, long calls, long failures,
                         long blocked, double failureRate, long openedAt, String note) {
        this.resource = resource;
        this.backend = backend;
        this.state = state;
        this.calls = calls;
        this.failures = failures;
        this.blocked = blocked;
        this.failureRate = failureRate;
        this.openedAt = openedAt;
        this.note = note;
    }

    public String getResource() {
        return resource;
    }

    public String getBackend() {
        return backend;
    }

    public String getState() {
        return state;
    }

    public long getCalls() {
        return calls;
    }

    public long getFailures() {
        return failures;
    }

    public long getBlocked() {
        return blocked;
    }

    public double getFailureRate() {
        return failureRate;
    }

    public long getOpenedAt() {
        return openedAt;
    }

    public String getNote() {
        return note;
    }

    /** 是否处于不可用状态。 */
    public boolean isUnavailable() {
        return "OPEN".equals(state);
    }
}
