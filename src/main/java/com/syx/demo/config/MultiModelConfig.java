package com.syx.demo.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MultiModelConfig {

    @Bean
    @Primary
    public ChatModel deepSeekChatModel(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String model) {

        // ⭐ 方式一：使用 OpenAiApi 的 Builder 模式（推荐）
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
    }

    // 智谱模型 Bean
    @Bean
    public ChatModel zhiPuChatModel(
            @Value("${spring.ai.zhipuai.base-url}") String baseUrl,
            @Value("${spring.ai.zhipuai.api-key}") String apiKey,
            @Value("${spring.ai.zhipuai.chat.options.model}") String model) {
        ZhiPuAiApi api = new ZhiPuAiApi(baseUrl, apiKey);
        return new ZhiPuAiChatModel(api,
                ZhiPuAiChatOptions.builder()
                        .model(model)
                        .build());
    }
}