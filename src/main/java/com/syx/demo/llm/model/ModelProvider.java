package com.syx.demo.llm.model;

/**
 * 接入协议。目前只需要两种：
 *
 * <ul>
 *   <li>{@link #OPENAI_COMPATIBLE} —— OpenAI 的 /chat/completions 协议。DeepSeek、硅基流动、
 *       vLLM / SGLang 部署的 Qwen 全部兼容，院内私有化模型基本都走这条路。</li>
 *   <li>{@link #ZHIPU} —— 智谱自己的 SDK（{@code ZhiPuAiApi}），因为它的鉴权和报文结构与 OpenAI 略有差异。</li>
 * </ul>
 */
public enum ModelProvider {
    OPENAI_COMPATIBLE,
    ZHIPU
}
