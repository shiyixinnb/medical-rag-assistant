package com.syx.demo.llm.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 注册表里的运行时模型句柄：配置快照 + 已经构建好的 Spring AI 对象。
 *
 * <p>ChatClient 是线程安全的，所以这里构建一次缓存起来复用；每个模型一个 client，
 * 避免在每次请求里重复 builder()。
 */
public final class ModelDefinition {

    private final String id;
    private final String displayName;
    private final ModelProvider provider;
    private final String baseUrl;
    private final String model;
    private final ModelCapability capability;
    private final int priority;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final String remark;

    private final ChatModel chatModel;
    private final ChatClient chatClient;

    public ModelDefinition(String id,
                           String displayName,
                           ModelProvider provider,
                           String baseUrl,
                           String model,
                           ModelCapability capability,
                           int priority,
                           int connectTimeoutSeconds,
                           int readTimeoutSeconds,
                           String remark,
                           ChatModel chatModel) {
        this.id = id;
        this.displayName = displayName == null || displayName.isBlank() ? id : displayName;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.capability = capability;
        this.priority = priority;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.remark = remark == null ? "" : remark;
        this.chatModel = chatModel;
        // ChatClient 无状态、线程安全，构建一次全局复用
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ModelProvider getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public ModelCapability getCapability() {
        return capability;
    }

    public int getPriority() {
        return priority;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public String getRemark() {
        return remark;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public ChatClient getChatClient() {
        return chatClient;
    }

    /** 脱敏后的 baseUrl，可以直接打日志 / 返回给前端。 */
    public String getMaskedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "-" : baseUrl;
    }

    @Override
    public String toString() {
        return "ModelDefinition{" + id + " -> " + provider + "/" + model + " [" + capability + "]}";
    }
}
