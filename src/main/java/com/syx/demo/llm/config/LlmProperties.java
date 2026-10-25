package com.syx.demo.llm.config;

import com.syx.demo.llm.model.ModelCapability;
import com.syx.demo.llm.model.ModelProvider;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 接入层的全部可配置项，前缀 {@code app.llm}。
 *
 * <p>设计要点：模型清单是<b>配置驱动</b>的 —— 新增一个院内私有化模型（例如 vLLM 部署的
 * Qwen）只需要在 application.yml 里加一段 {@code app.llm.models.xxx}，不用改一行 Java 代码，
 * 也不用重新打包。未填 api-key 或显式 {@code enabled: false} 的模型会在注册阶段被静默跳过。
 */
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /** 缺省模型 id（route 阶段找不到更合适的候选时兜底）。 */
    private String defaultModel = "deepseek-chat";

    /** 模型清单：id -> 定义。用 LinkedHashMap 保证启动日志里的顺序稳定。 */
    private Map<String, ModelDef> models = new LinkedHashMap<>();

    /** 主备降级链：模型 id -> 它挂掉之后按顺序尝试的候选。 */
    private Map<String, List<String>> fallback = new LinkedHashMap<>();

    private Routing routing = new Routing();
    private Breaker breaker = new Breaker();
    private Retry retry = new Retry();
    private Session session = new Session();
    private Rag rag = new Rag();
    private Domain domain = new Domain();

    // ------------------------------------------------------------------ 嵌套配置

    /** 能力路由开关与阈值。 */
    @Getter
    @Setter
    public static class Routing {
        /** 关掉后所有问题都走 defaultModel，便于做 A/B 对比。 */
        private boolean enabled = true;
        /** 复杂度判定为 REASONING 时优先使用的模型 id。 */
        private String reasoningModel = "deepseek-reasoner";
        /** 常规问答模型 id。 */
        private String generalModel = "deepseek-chat";
        /** 极短且无推理诉求的问题可以走更便宜的模型（留空表示不启用）。 */
        private String fastModel = "";
        /** 问题长度（字符）超过该值视为推理密集问题。 */
        private int reasoningLengthThreshold = 80;
    }

    /** 熔断（降级）配置。 */
    @Getter
    @Setter
    public static class Breaker {
        /** auto / sentinel / simple。auto 表示类路径上有 Sentinel 就用它。 */
        private String backend = "auto";
        /** 失败率阈值（0~1），超过即打开熔断。 */
        private double failureRateThreshold = 0.5;
        /** 统计窗口内最少请求数，样本不足不触发熔断。 */
        private int minRequestAmount = 5;
        /** 统计窗口长度（毫秒）。 */
        private int statIntervalMs = 10_000;
        /** 熔断打开后的静默期（秒），到点进入半开探测。 */
        private int timeWindowSeconds = 20;
        /** 慢调用 RT 阈值（毫秒），超过即记为慢调用。 */
        private int slowCallRtMs = 15_000;
    }

    /** 重试配置：只对同一个模型重试，重试耗尽才降级到备选模型。 */
    @Getter
    @Setter
    public static class Retry {
        /** 单模型最大尝试次数（1 表示不重试，直接降级）。 */
        private int maxAttempts = 2;
        /** 首次退避间隔（毫秒），按 2 倍指数增长。 */
        private long backoffMs = 300;
    }

    /** 多轮会话上下文配置。 */
    @Getter
    @Setter
    public static class Session {
        /** 会话空闲过期时间（分钟）。 */
        private long ttlMinutes = 120;
        /** 送入模型的历史消息条数上限。 */
        private int historyTurns = 10;
        /** Redis 里最多保留的历史消息条数（超出裁掉最旧的）。 */
        private int maxStoredMessages = 40;
        /** Redis key 前缀。 */
        private String keyPrefix = "app:llm:";
    }

    /** RAG 检索配置。 */
    @Getter
    @Setter
    public static class Rag {
        private int topK = 4;
        private double similarityThreshold = 0.5;
        /** 单条召回片段注入 Prompt 时的最大字符数，防止撑爆上下文。 */
        private int maxSnippetChars = 700;
    }

    /** 数据域隔离配置。 */
    @Getter
    @Setter
    public static class Domain {
        /** 默认是否允许访问患者隐私域。生产环境建议 false，由接口显式放开。 */
        private boolean allowPatientByDefault = false;
        /** 公共知识域标识。 */
        private String publicDomainKey = "PUBLIC";
        /** 患者隐私域标识。 */
        private String patientDomainKey = "PATIENT";
        /**
         * 数据域元数据字段名。向量库里每条 Document 都会带 {@code domain} 与 {@code tenantId}，
         * 检索时用过滤表达式强约束，模型层拿不到越域的片段。
         */
        private String domainField = "domain";
        private String tenantField = "tenantId";
        /** 患者标识字段名。 */
        private String patientField = "patientId";
        /**
         * 从问题里识别「显式点名的患者标识」的正则。
         *
         * <p>用途：用户问"患者 P-9001 的诊断是什么"，如果 P-9001 不属于当前 tenantId，
         * 我们要能<b>确定性地</b>拒答，而不是依赖模型自己发现"资料里没有这个人"。
         * 识别到标识后检索会按其收敛（多带一层 {@code patientId == 'xxx'} 过滤），
         * 查不到就走既有的"无该患者资料 → 拒答"路径。
         *
         * <p>默认正则匹配 {@code P-1001} 这类写法，且<b>不会</b>误伤 {@code HOSP-A}（字母段后必须是数字）。
         */
        private String patientIdPattern = "(?<![A-Za-z0-9])[A-Za-z]{1,4}-[0-9]{2,}(?![0-9])";
    }

    // ------------------------------------------------------------------ 模型定义

    /** 单个模型的接入参数。 */
    @Getter
    @Setter
    public static class ModelDef {
        /** 展示名，用于日志和 /models 接口。 */
        private String displayName = "";
        /** 协议实现：openai-compatible（DeepSeek/Qwen/vLLM 等）或 zhipu。 */
        private ModelProvider provider = ModelProvider.OPENAI_COMPATIBLE;
        private String baseUrl = "";
        private String apiKey = "";
        /** 真正传给服务商的模型名。 */
        private String model = "";
        /** 能力标签，决定它会不会被复杂推理类问题选中。 */
        private ModelCapability capability = ModelCapability.GENERAL;
        /** 数值越小越优先。 */
        private int priority = 100;
        /** 连接超时（秒）。 */
        private int connectTimeoutSeconds = 5;
        /** 读超时（秒）—— 这是"DeepSeek 接口超时自动切 GLM"的判定依据。 */
        private int readTimeoutSeconds = 30;
        /** 显式关闭该模型。 */
        private boolean enabled = true;
        /** 备注，例如"院内私有化 vLLM 部署"。 */
        private String remark = "";

        /** 配置是否完整可用：有模型名、有 key、有 baseUrl。 */
        public boolean looksUsable() {
            if (!enabled || model == null || model.isBlank()) {
                return false;
            }
            if (apiKey == null || apiKey.isBlank()) {
                return false;
            }
            return provider == ModelProvider.ZHIPU || (baseUrl != null && !baseUrl.isBlank());
        }
    }

    // ------------------------------------------------------------------ getter/setter

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, ModelDef> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelDef> models) {
        this.models = models;
    }

    public Map<String, List<String>> getFallback() {
        return fallback;
    }

    public void setFallback(Map<String, List<String>> fallback) {
        this.fallback = fallback;
    }

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public void setBreaker(Breaker breaker) {
        this.breaker = breaker;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Rag getRag() {
        return rag;
    }

    public void setRag(Rag rag) {
        this.rag = rag;
    }

    public Domain getDomain() {
        return domain;
    }

    public void setDomain(Domain domain) {
        this.domain = domain;
    }

    /** 取某个模型配置好的降级链（可能是空列表）。 */
    public List<String> fallbackOf(String modelId) {
        List<String> list = fallback.get(modelId);
        return list == null ? new ArrayList<>() : list;
    }
}
