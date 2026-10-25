package com.syx.demo.llm.guard;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 模型调用护栏：负责"熔断"这一层，也就是决定<b>还要不要调用某个模型</b>。
 *
 * <p>为什么不让 ChatFacade 直接依赖 Sentinel？因为 Sentinel 在急诊这种院内环境里不一定能上
 * （有的医院禁止引入阿里系组件、有的内网拿不到 jar）。所以这里定一个最小接口：
 * 有 Sentinel 就用它的官方实现（带完整的滑动窗口、半开探测），没有就用自研的简化版，
 * <b>上层编排代码一行都不用改</b>。
 *
 * <p>接口刻意只暴露一个 {@code execute}：把"申请许可 → 执行 → 记账 → 释放"四步封在一起，
 * 避免调用方忘记 exit 导致 Sentinel 的 ThreadLocal 泄漏（这是个非常隐蔽的线上事故来源）。
 *
 * <p><b>流式调用为什么要单独一个入口（{@link #guardStream}）？</b>
 * 因为 {@code execute} 依赖"同步返回即调用结束"这个前提，而流式是一次订阅、分多次回调、
 * 结束在另一个线程上。如果直接拿 {@code execute} 包 {@code Supplier<Flux>}，
 * 它会在流还没开始消费时就记一次"成功"，导致流式的失败永远统计不到、熔断永远打不开。
 * 所以流式必须用能"跨线程配对"的机制（Sentinel 的 {@code AsyncEntry} 就是为此设计的）。
 */
public interface ModelCallGuard {

    /** 实现名：{@code sentinel} 或 {@code simple}，用于日志与 /models 接口展示。 */
    String backend();

    /**
     * 在护栏保护下执行一次模型调用。
     *
     * @param resource 资源名，直接用模型 id，这样熔断粒度天然就是"单个模型"
     * @param action   真正的调用逻辑
     * @throws ModelBlockedException 当前处于熔断/限流状态，本次调用被拒（应立刻降级）
     */
    <T> T execute(String resource, java.util.function.Supplier<T> action) throws ModelBlockedException;

    /** 各资源的实时熔断快照，供运维接口展示。 */
    Map<String, GuardSnapshot> snapshots();

    /** 某个资源当前是否处于"打开"状态（不会产生副作用，可安全调用）。 */
    boolean isOpen(String resource);

    /**
     * 在护栏保护下执行一次<b>流式</b>模型调用。
     *
     * <p>与 {@link #execute} 的差别只有一处：调用的"结束"时刻被推迟到流真正终止
     * （onComplete / onError / cancel）的时候，因此熔断统计对慢调用、对中途断流同样有效。
     *
     * <p>实现约定：本方法<b>必须同步地</b>完成"许可申请"，也就是在返回 Flux 之前就能判定
     * 是否被熔断——这样调用方可以在"没有任何内容发给用户之前"安全地降级到下一个模型。
     *
     * <p>默认实现只做一次 {@link #isOpen} 检查、不做统计：宁可放行（少拦一次）也不误拦，
     * 对自研实现而言这是最保守的行为。两个内置实现都覆盖了它。
     *
     * @param resource 资源名（模型 id）
     * @param action   构造上游 Flux 的逻辑（注意：真实请求发生在订阅时）
     * @throws ModelBlockedException 当前处于熔断/限流状态，本次调用被拒（应立刻降级）
     */
    default <T> Flux<T> guardStream(String resource, Supplier<Flux<T>> action) throws ModelBlockedException {
        if (isOpen(resource)) {
            throw new ModelBlockedException(resource,
                    "模型 " + resource + " 处于熔断打开状态，本次流式调用被拒并直接降级", null);
        }
        return action.get();
    }
}
