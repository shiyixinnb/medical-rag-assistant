package com.syx.demo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 最小可用 RAG 示例 —— 想理解 RAG，从这 40 行开始读。
 *
 * <p>整条链路只有三步：{@code ChatClient} + {@code QuestionAnswerAdvisor} + 一句提问。
 * 检索、拼接上下文、注入 Prompt 全被 Advisor 包掉了，所以这里看不到"向量"和"Prompt"的影子。
 *
 * <p><b>它和正式链路（{@code llm/} 包）的关系：</b>
 * <pre>
 *   本类                       正式链路
 *   ─────────────────────────  ──────────────────────────────────
 *   ChatClient 直调             ChatFacade（路由 / 重试 / 熔断 / 降级）
 *   QuestionAnswerAdvisor       DomainAwareRetriever（授权 / 过滤 / 复核）
 *   写死 topK=4、阈值 0.7        app.llm.rag.* 配置驱动
 *   无会话、无引用校验           会话 + META 解析 + 引用编号校验
 * </pre>
 *
 * <p>读法建议：先跑通它（能回答就说明向量库是通的），再去读正式链路，
 * 你会清楚地知道"多出来的那些类到底在解决什么问题"。
 */
@RestController
@RequestMapping("/api/emergency")
public class EmergencyRagController {

    private final ChatClient deepSeekClient;
    private final ChatClient zhiPuClient;
    private final VectorStore vectorStore;

    // 与 ChatController 一致：通过 @Qualifier 注入两个聊天模型，构建两个 ChatClient
    public EmergencyRagController(
            @Qualifier("deepSeekChatModel") ChatModel deepSeekModel,
            @Qualifier("zhiPuChatModel") ChatModel zhiPuModel,
            VectorStore vectorStore) {
        this.deepSeekClient = ChatClient.builder(deepSeekModel).build();
        this.zhiPuClient = ChatClient.builder(zhiPuModel).build();
        this.vectorStore = vectorStore;
    }

    /**
     * 急救知识问答（带 RAG 检索）
     * 通过 model 参数选择聊天模型：model=deepseek（默认）或 model=zhipu
     * 例如：/api/emergency/ask?question=中暑了怎么办&model=deepseek
     */
    @GetMapping("/ask")
    public String ask(
            @RequestParam(defaultValue = "deepseek") String model,
            @RequestParam String question) {

        ChatClient client = "zhipu".equalsIgnoreCase(model) ? zhiPuClient : deepSeekClient;

        // 构建 RAG Advisor（M7 版 API：searchRequest 接收 SearchRequest 对象）
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(4)                 // 返回最相关的4个片段
                        .similarityThreshold(0.7) // 相似度阈值
                        .build())
                .build();

        return client.prompt()
                .user(question)
                .advisors(qaAdvisor)
                .call()
                .content();
    }
}
