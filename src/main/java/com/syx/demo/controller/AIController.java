package com.syx.demo.controller;

import com.syx.demo.tool.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 最小示例：ChatClient + {@code @Tool}（Function Calling）。
 *
 * <p>这是项目最早的一版 demo，只验证一件事：<b>模型能不能调用本地方法</b>。
 * {@code .tools(weatherTool)} 这一行就是全部魔法——Spring AI 会把
 * {@link WeatherTool} 上的 {@code @Tool} 描述转成函数声明发给模型，模型决定什么时候调。
 *
 * <p>它没有 RAG、没有会话、没有容错。正式链路在 {@code com.syx.demo.llm} 包下，
 * 保留这个类是为了对照"10 行 demo"和"生产版"的差距。
 */
@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final ChatClient chatClient;
    private final WeatherTool weatherTool;

    public AIController(ChatClient.Builder chatClientBuilder, WeatherTool weatherTool) {
        this.chatClient = chatClientBuilder.build();
        this.weatherTool = weatherTool;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam(defaultValue = "你好") String message) {
        return chatClient.prompt()
                .user(message)
                .tools(weatherTool)  // 注册工具
                .call()
                .content();
    }
}