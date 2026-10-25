package com.syx.demo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 早期多模型 demo：手动构建两个 {@code ChatClient}，验证"同一份代码能切换模型"。
 *
 * <p>注意它是靠 {@code @Qualifier("deepSeekChatModel")} 指定 Bean 的；而正式链路把这件事
 * 做成了<b>配置驱动</b>（{@code app.llm.models} + {@code ModelRegistry} + {@code ChatFacade}）。
 *
 * <p>把这两个实现对比着看，能直观体会到"能跑"和"可运营"的差别：
 * 前者加一个模型要改代码、重新发版；后者只需要改 yml。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient deepSeekClient;
    private final ChatClient zhiPuClient;

    // 通过 @Qualifier 指定注入哪个 Bean（Bean 名称就是方法名）
    public ChatController(
            @Qualifier("deepSeekChatModel") ChatModel deepSeekModel,
            @Qualifier("zhiPuChatModel") ChatModel zhiPuModel) {
        this.deepSeekClient = ChatClient.builder(deepSeekModel).build();
        this.zhiPuClient = ChatClient.builder(zhiPuModel).build();
    }

    // ========== 单独测试各模型 ==========

    // 测试 DeepSeek
    @GetMapping("/deepseek")
    public String chatDeepSeek(@RequestParam(defaultValue = "你好，请用一句话介绍你自己") String message) {
        return deepSeekClient.prompt()
                .user(message)
                .call()
                .content();
    }

    // 测试智谱
    @GetMapping("/zhipu")
    public String chatZhiPu(@RequestParam(defaultValue = "你好，请用一句话介绍你自己") String message) {
        return zhiPuClient.prompt()
                .user(message)
                .call()
                .content();
    }

    // ========== 动态选择模型 ==========

    // 通过参数指定用哪个模型：?model=deepseek 或 ?model=zhipu
    @GetMapping("/chat")
    public String chat(
            @RequestParam(defaultValue = "deepseek") String model,
            @RequestParam(defaultValue = "你好") String message) {

        ChatClient client;
        if ("zhipu".equalsIgnoreCase(model)) {
            client = zhiPuClient;
        } else {
            client = deepSeekClient; // 默认 DeepSeek
        }

        return client.prompt()
                .user(message)
                .call()
                .content();
    }
}