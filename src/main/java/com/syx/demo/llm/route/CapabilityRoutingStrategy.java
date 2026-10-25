package com.syx.demo.llm.route;

import com.syx.demo.llm.config.LlmProperties;
import com.syx.demo.llm.model.ModelCapability;
import com.syx.demo.llm.model.ModelDefinition;
import com.syx.demo.llm.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认路由策略：<b>按问题类型做能力路由</b>。
 *
 * <p>核心目标是平衡成本与响应速度：
 * <ul>
 *   <li>鉴别诊断、机制推演、多因素权衡 → 走 REASONING（DeepSeek-R1 / 院内 Qwen-Reasoner），
 *       这些问题"答得快"没有价值，答得准才有价值；</li>
 *   <li>指南条文、药品说明、通用问答 → 走 GENERAL（GLM-4 / deepseek-chat），
 *       事实检索类问题用推理模型纯属浪费；</li>
 *   <li>极短输入（"嗯"、"继续"、"谢谢"）→ 走 FAST（如果配了），否则回落 GENERAL。</li>
 * </ul>
 *
 * <p>选不到目标能力的模型时不会报错，而是逐级回落：REASONING → GENERAL → 缺省模型。
 * 医院内网经常出现"推理模型还没申请下来"的情况，回落比报错实用得多。
 */
@Component
public class CapabilityRoutingStrategy implements RoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRoutingStrategy.class);

    private final ModelRegistry registry;
    private final LlmProperties properties;

    public CapabilityRoutingStrategy(ModelRegistry registry, LlmProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "capability-routing";
    }

    @Override
    public RoutingDecision route(IntentSignal intent, String question) {
        LlmProperties.Routing cfg = properties.getRouting();

        // 开关关掉 = 全部走缺省模型，方便做"路由到底有没有用"的 A/B 对比
        if (!cfg.isEnabled()) {
            ModelDefinition fallback = pickFirstExisting(List.of());
            return decide(intent, fallback, "路由已关闭（app.llm.routing.enabled=false），全部走缺省模型");
        }

        QuestionIntent qi = intent.getIntent();

        // ① 极短输入优先走便宜模型
        if (cfg.getFastModel() != null && !cfg.getFastModel().isBlank()
                && question != null && question.trim().length() <= 8
                && qi == QuestionIntent.CHITCHAT) {
            ModelDefinition fast = registry.find(cfg.getFastModel()).orElse(null);
            if (fast != null) {
                return decide(intent, fast, "极短闲聊输入，优先使用轻量模型降低成本");
            }
        }

        // ② 推理密集问题 → 推理模型
        if (qi.isPreferReasoning()) {
            ModelDefinition reasoning = pickByCapability(ModelCapability.REASONING, cfg.getReasoningModel());
            if (reasoning != null) {
                return decide(intent, reasoning,
                        qi.getLabel() + "属于推理密集型课程，优先使用 " + reasoning.getId() + " 保证准确率");
            }
            // 推理模型没配 → 退回常规模型，但把理由说清楚，别让人误以为路由没生效
            ModelDefinition general = pickByCapability(ModelCapability.GENERAL, cfg.getGeneralModel());
            return decide(intent, general,
                    "未注册可用的推理模型，回落常规模型 " + (general == null ? "缺省" : general.getId()));
        }

        // ③ 常规问答 → 常规模型
        ModelDefinition general = pickByCapability(ModelCapability.GENERAL, cfg.getGeneralModel());
        if (general != null) {
            return decide(intent, general, qi.getLabel() + "使用常规模型平衡成本与响应速度");
        }
        ModelDefinition any = pickFirstExisting(List.of());
        return decide(intent, any, "未注册可用的常规模型，回落缺省模型");
    }

    @Override
    public RoutingDecision explicit(ModelDefinition model, IntentSignal intent) {
        return new RoutingDecision(intent, model, registry.resolveChain(model.getId()),
                "调用方显式指定模型 " + model.getId());
    }

    /**
     * 按能力挑模型：先找配置里点名的那一个，其次在同能力模型里按优先级取第一个，
     * 最后再逐级回落，保证任何配置状态下都能选出一个模型。
     */
    private ModelDefinition pickByCapability(ModelCapability capability, String preferredId) {
        if (preferredId != null && !preferredId.isBlank()) {
            ModelDefinition preferred = registry.find(preferredId).orElse(null);
            if (preferred != null && preferred.getCapability() == capability) {
                return preferred;
            }
            if (preferred != null) {
                // 配置点名的模型能力标签不一致：以实际能力标签为准则会绕开路由，所以这里只警告不采用
                log.warn("[LLM] 配置的 {} 能力为 {}，与期望的 {} 不一致，改为按能力自动挑选",
                        preferredId, preferred.getCapability(), capability);
            }
        }
        List<ModelDefinition> candidates = registry.byCapability(capability);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 兜底：优先缺省模型，其次注册表里优先级最高的那个。 */
    private ModelDefinition pickFirstExisting(List<String> ignored) {
        ModelDefinition def = registry.find(properties.getDefaultModel()).orElse(null);
        if (def != null) {
            return def;
        }
        return registry.all().stream()
                .min(java.util.Comparator.comparingInt(ModelDefinition::getPriority))
                .orElse(null);
    }

    private RoutingDecision decide(IntentSignal intent, ModelDefinition primary, String reason) {
        if (primary == null) {
            throw new IllegalStateException("没有任何可用模型，请检查 app.llm.models 配置");
        }
        return new RoutingDecision(intent, primary, registry.resolveChain(primary.getId()), reason);
    }
}
