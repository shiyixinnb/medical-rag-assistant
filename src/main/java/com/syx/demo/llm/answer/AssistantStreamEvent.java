package com.syx.demo.llm.answer;

import java.util.Map;

/**
 * 流式回答向前端推送的事件。
 *
 * <p>四类事件构成一次完整的流式交互：
 *
 * <ol>
 *   <li>{@code meta} —— 在调用模型<b>之前</b>就发出去：意图、数据域、召回了哪些资料。
 *       医生能立刻看到"系统正在查什么"，而不是对着空白等十几秒；</li>
 *   <li>{@code delta} —— 正文增量，一次交互里可能有很多条；</li>
 *   <li>{@code done} —— 完整结果，data 的类型就是 {@link RagAnswer}；</li>
 *   <li>{@code error} —— 不可恢复的错误（前端展示提示并结束）。</li>
 * </ol>
 *
 * <p>{@code done} 里带的是<b>与非流式接口完全相同的</b> {@link RagAnswer}，
 * 这样前端两条路径能共用一套渲染逻辑，不会出现"流式显示的和非流式不一样"的问题。
 *
 * <p><b>注意 {@link #data()} 是"逻辑负载"，不是"线路负载"。</b>
 * 真正写进 SSE 的是 {@link #wirePayload()}——两者对 delta 并不相同，原因见该方法注释。
 *
 * @param event 事件名：meta / delta / done / error
 * @param data  逻辑负载（meta 是 Map，delta 是文本，done 是 RagAnswer，error 是文本）
 */
public record AssistantStreamEvent(String event, Object data) {

    /** 调用模型之前先行下发的过程信息。 */
    public static AssistantStreamEvent meta(Object data) {
        return new AssistantStreamEvent("meta", data);
    }

    /** 一段正文增量。 */
    public static AssistantStreamEvent delta(String text) {
        return new AssistantStreamEvent("delta", text);
    }

    /** 完整结果（RagAnswer）。 */
    public static AssistantStreamEvent done(Object answer) {
        return new AssistantStreamEvent("done", answer);
    }

    /** 错误提示。 */
    public static AssistantStreamEvent error(String message) {
        return new AssistantStreamEvent("error", message);
    }

    /**
     * 真正写进 SSE 的负载：<b>把裸字符串包成 JSON 对象</b>。
     *
     * <p>为什么必须包这一层——Spring 的 {@code SseEmitter} 对 {@code String} 类型的 data
     * <b>原样写出、不做 JSON 序列化</b>（即便指定了 {@code APPLICATION_JSON}），
     * 于是正文会踩两个 SSE 规范层面的坑，而且这两个坑在服务端<b>看不出来</b>：
     *
     * <ol>
     *   <li><b>行首空格被吃</b>：规范要求客户端"剥掉字段值开头的单个 U+0020 空格"，
     *       而 Spring 写出的是 {@code data:} 直接接数据、并不额外补一个分隔空格。
     *       于是正文里真实存在的行首空格（模型逐字吐出的 {@code " 既"}），
     *       会被<b>任何规范客户端（含浏览器原生 EventSource）当成分隔符剥掉</b>。
     *       本机实测：435 个 delta 里有 10 个以空格开头、13 个以空格结尾，
     *       拼出来的正文会变成 {@code "-中暑"} 这种挤在一起的形态。</li>
     *   <li><b>换行被拆分</b>：正文里的 {@code \n} 会被拆成多个 {@code data:} 行
     *       （实测 18 个空值行），客户端必须按规范用 {@code \n} 逐行重新拼接，
     *       少拼一次就丢一个换行（表现是整段挤成一行）。</li>
     * </ol>
     *
     * <p>包成 JSON 后，空格与换行都成了 JSON 字符串内部的转义字符：
     * 首字符恒为 {@code \{}，<b>字节级保真</b>，也不会再触发多行 data 拆分。
     *
     * <p>因此本接口对外承诺：<b>每个 SSE 事件的 data 都是且只是一个 JSON 对象</b>；
     * delta 的键名固定为 {@code text}。
     */
    public Object wirePayload() {
        return data instanceof String text ? Map.of("text", text) : data;
    }
}
