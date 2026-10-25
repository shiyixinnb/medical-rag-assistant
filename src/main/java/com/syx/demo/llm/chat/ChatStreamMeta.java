package com.syx.demo.llm.chat;

import java.util.List;

/**
 * 流式回答<b>结束时</b>才能给出的元信息。
 *
 * <p>流式接口有个天然的困难：正文是逐字吐出来的，但"用了哪个模型""有没有降级""试了几次"
 * 这些结构化信息要到最后一个字才知道。所以统一收在流的末尾一次性下发，
 * 前端拿到之后拼成一个完整的回答对象，就能复用非流式的展示逻辑。
 *
 * @param modelId        实际回答的模型
 * @param routedModelId  路由选中的主模型（与 modelId 不同即为发生了降级）
 * @param degraded       是否发生主备降级
 * @param attempts       每个模型的尝试记录（失败原因也在这里）
 * @param routeReason    路由理由（"为什么走了这个模型"必须可解释）
 * @param costMs         整个流的耗时
 */
public record ChatStreamMeta(String modelId, String routedModelId, boolean degraded,
                             List<ModelAttempt> attempts, String routeReason, long costMs) {
}
