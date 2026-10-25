package com.syx.demo.llm.model;

import com.syx.demo.llm.config.LlmProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallbackResolver;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 模型注册表：把 {@code app.llm.models.*} 的配置变成可用的 Spring AI 模型实例。
 *
 * <p>三件必须在这里做对、否则后面全错的事：
 *
 * <ol>
 *   <li><b>关掉 Spring AI 的内部重试。</b>M7 的 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} 是
 *       <i>10 次尝试、指数退避 2s×5 最高 180s</i>。如果不禁用，DeepSeek 一旦 5xx，
 *       请求会在模型内部自己重试好几分钟，我们"超时自动切 GLM"的逻辑根本轮不到执行。
 *       所以这里给每个模型注入 {@code maxAttempts=1} 的 RetryTemplate，
 *       重试与降级的控制权全部收归 {@code ChatFacade}。</li>
 *   <li><b>按模型配超时。</b>推理模型的读超时（60s）和常规模型（30s）必须分开，
 *       否则要么推理模型被误杀，要么常规模型卡死太久。</li>
 *   <li><b>坏配置不阻断启动。</b>院内私有化模型常常"先占位、后开通"，
 *       缺 key / 缺 baseUrl 的条目直接跳过并在日志里说明原因。</li>
 * </ol>
 *
 * <p><b>【阅读导航】</b>这个类只在启动时"写"一次，运行时全是读：
 * <pre>
 *   @PostConstruct init()
 *     └ 遍历 app.llm.models
 *        ├ looksUsable() 不够用 → 记进 skipped（含原因），继续下一个
 *        └ build()       够用   → 造 ChatModel → 存进 models 表
 * </pre>
 * 运行时用到的只有三个查询：{@link #find}（按 id 找）、{@link #byCapability}（按能力找）、
 * {@link #resolveChain}（展开主备降级链）。想加一个模型，改 yml 就够，不用动这个类。
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final LlmProperties properties;

    /** id -> 可用模型。LinkedHashMap 保持配置顺序，便于日志与前端展示。 */
    private final Map<String, ModelDefinition> models = new LinkedHashMap<>();

    /** 被跳过的配置及原因，供 /models 接口展示，方便运维定位"为什么模型没生效"。 */
    private final Map<String, String> skipped = new LinkedHashMap<>();

    public ModelRegistry(LlmProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 初始化

    @PostConstruct
    public void init() {
        for (Map.Entry<String, LlmProperties.ModelDef> entry : properties.getModels().entrySet()) {
            String id = entry.getKey();
            LlmProperties.ModelDef def = entry.getValue();
            try {
                if (!def.looksUsable()) {
                    skipped.put(id, skipReason(def));
                    continue;
                }
                models.put(id, build(id, def));
            }
            catch (Exception ex) {
                // 单个模型构建失败不能拖垮整个应用启动
                skipped.put(id, "构建失败: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
                log.warn("[LLM] 模型 {} 构建失败，已跳过：{}", id, ex.getMessage());
            }
        }
        printRegistryTable();
    }

    private String skipReason(LlmProperties.ModelDef def) {
        if (!def.isEnabled()) {
            return "配置中 enabled=false";
        }
        if (def.getModel() == null || def.getModel().isBlank()) {
            return "未配置 model 名称";
        }
        if (def.getApiKey() == null || def.getApiKey().isBlank()) {
            return "未配置 api-key（私有化模型先占位即可，开通后填 key 自动生效）";
        }
        return "未配置 base-url";
    }

    /** 按协议类型调用对应的 Spring AI 实现。 */
    private ModelDefinition build(String id, LlmProperties.ModelDef def) {
        ChatModel chatModel = switch (def.getProvider()) {
            case OPENAI_COMPATIBLE -> buildOpenAiCompatible(def);
            case ZHIPU -> buildZhipu(def);
        };
        return new ModelDefinition(id, def.getDisplayName(), def.getProvider(), def.getBaseUrl(),
                def.getModel(), def.getCapability(), def.getPriority(),
                def.getConnectTimeoutSeconds(), def.getReadTimeoutSeconds(), def.getRemark(), chatModel);
    }

    /** DeepSeek / 硅基流动 / 院内 vLLM 部署的 Qwen 等，统一走 OpenAI 兼容协议。 */
    private ChatModel buildOpenAiCompatible(LlmProperties.ModelDef def) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(def.getBaseUrl())
                .apiKey(def.getApiKey())
                .restClientBuilder(restClientBuilder(def))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(def.getModel())
                        .build())
                // ⭐ 关掉 M7 默认的 10 次内部重试，重试/降级由 ChatFacade 统一编排
                .retryTemplate(noRetryTemplate())
                .build();
    }

    /** 智谱 SDK。注意它必须注入非空的 FunctionCallbackResolver，这里给一个拒绝式实现。 */
    private ChatModel buildZhipu(LlmProperties.ModelDef def) {
        ZhiPuAiApi api = new ZhiPuAiApi(def.getBaseUrl(), def.getApiKey(), restClientBuilder(def));

        FunctionCallbackResolver noToolsResolver = name -> {
            throw new IllegalStateException("本接入层未启用智谱工具调用，收到非法调用请求：" + name);
        };

        return new ZhiPuAiChatModel(
                api,
                ZhiPuAiChatOptions.builder()
                        .model(def.getModel())
                        .build(),
                noToolsResolver,
                new ArrayList<>(),
                noRetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    /**
     * 单模型级别的 HTTP 超时。
     *
     * <p>"接口超时自动降级"能不能生效，完全取决于这里的读超时 —— 读超时必须明显小于
     * 上游网关的超时时间，否则降级还没发生、调用方先超时了。
     */
    private RestClient.Builder restClientBuilder(LlmProperties.ModelDef def) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, def.getConnectTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, def.getReadTimeoutSeconds())));
        return RestClient.builder().requestFactory(factory);
    }

    /** maxAttempts=1 = 不重试。 */
    private RetryTemplate noRetryTemplate() {
        return RetryTemplate.builder().maxAttempts(1).build();
    }

    private void printRegistryTable() {
        log.info("========== LLM 接入层模型注册表 ==========");
        for (ModelDefinition d : models.values()) {
            // 注意：SLF4J 只认 {}，不支持 {:<20} 这类格式说明符，所以列宽要自己补空格
            log.info("  [可用] {} | {} | {} | 优先级 {} | 连接/读超时 {}s/{}s | {}",
                    pad(d.getId(), 20),
                    d.getProvider() + ":" + d.getModel(),
                    d.getCapability().getLabel(),
                    d.getPriority(),
                    d.getConnectTimeoutSeconds(),
                    d.getReadTimeoutSeconds(),
                    d.getRemark());
        }
        skipped.forEach((id, reason) -> log.info("  [跳过] {} 原因：{}", pad(id, 20), reason));
        log.info("  可用模型 {} 个；缺省模型 {}（主备降级链见 app.llm.fallback）",
                models.size(), properties.getDefaultModel());
        log.info("=========================================");
    }

    /** 手工补空格对齐：SLF4J 不做格式化，中文宽度也不可控，这里只求大致可读。 */
    private String pad(String value, int width) {
        StringBuilder sb = new StringBuilder(value == null ? "" : value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 查询

    public Optional<ModelDefinition> find(String id) {
        return Optional.ofNullable(models.get(id));
    }

    /** 取模型，不存在直接抛异常 —— 用于"配置错了就该立刻炸"的启动期校验。 */
    public ModelDefinition require(String id) {
        ModelDefinition def = models.get(id);
        if (def == null) {
            throw new IllegalStateException("模型 " + id + " 未注册。可用模型："
                    + models.keySet() + "；已跳过：" + skipped);
        }
        return def;
    }

    public boolean contains(String id) {
        return models.containsKey(id);
    }

    public Collection<ModelDefinition> all() {
        return models.values();
    }

    public Map<String, String> getSkipped() {
        return skipped;
    }

    public LlmProperties getProperties() {
        return properties;
    }

    /** 按能力筛选，按优先级升序 —— 数值小的先用。 */
    public List<ModelDefinition> byCapability(ModelCapability capability) {
        return models.values().stream()
                .filter(m -> m.getCapability() == capability)
                .sorted(Comparator.comparingInt(ModelDefinition::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * 展开某个模型的完整候选链：它自己 + 配置的降级链（去重、剔除未注册项）。
     * 这一步是"主备自动切换"的数据来源。
     */
    public List<ModelDefinition> resolveChain(String modelId) {
        List<ModelDefinition> chain = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        ids.add(modelId);
        ids.addAll(properties.fallbackOf(modelId));
        // 配置里可能漏配降级链，兜底追加缺省模型，保证总有备胎
        if (!ids.contains(properties.getDefaultModel())) {
            ids.add(properties.getDefaultModel());
        }
        for (String id : ids) {
            if (chain.stream().anyMatch(m -> m.getId().equals(id))) {
                continue;
            }
            find(id).ifPresent(chain::add);
        }
        return chain;
    }
}
