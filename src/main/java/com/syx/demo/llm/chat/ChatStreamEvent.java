package com.syx.demo.llm.chat;

/**
 * 流式调用产出的事件，只有两种：
 *
 * <ul>
 *   <li>{@link #delta(String)} —— 一段增量文本（模型刚生成的那几个字）；</li>
 *   <li>{@link #finished(ChatStreamMeta)} —— 流的终点，携带"用了哪个模型、有没有降级、耗时"。</li>
 * </ul>
 *
 * <p>为什么不让 {@code stream()} 直接返回 {@code Flux&lt;String&gt;}？因为调用方（服务层/接口层）
 * 需要知道<b>最后到底是谁回答的、有没有降级</b>——这是医疗系统的可审计要求，
 * 不能在流式的过程中丢掉。用一个显式的"终点事件"比在流外面挂一个可变引用干净得多
 * （也避免了 Reactor 里常见的"元信息还没写进去，下游就先把响应用完了"的竞态）。
 *
 * @param delta 增量文本，仅 delta 事件非空
 * @param meta  结束元信息，仅 finished 事件非空
 */
public record ChatStreamEvent(String delta, ChatStreamMeta meta) {

    /** 一段增量文本。 */
    public static ChatStreamEvent delta(String text) {
        return new ChatStreamEvent(text, null);
    }

    /** 流结束（成功）。 */
    public static ChatStreamEvent finished(ChatStreamMeta meta) {
        return new ChatStreamEvent(null, meta);
    }

    public boolean isDelta() {
        return delta != null;
    }

    public boolean isFinished() {
        return meta != null;
    }
}
