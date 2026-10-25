package com.syx.demo.llm.route;

import com.syx.demo.llm.model.ModelDefinition;
import com.syx.demo.llm.model.ModelRegistry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 路由决策结果：主模型 + 完整降级链 + 决策理由。
 *
 * <p>降级链在路由阶段就一次性展开，而不是等主模型挂了再去问注册表 ——
 * 因为调用时要的是"下一个候选是谁"，这个列表在决策时确定下来最省事也最好排查。
 */
public final class RoutingDecision {

    private final IntentSignal intent;
    private final ModelDefinition primary;
    private final List<ModelDefinition> chain;
    private final String reason;

    public RoutingDecision(IntentSignal intent, ModelDefinition primary,
                           List<ModelDefinition> chain, String reason) {
        this.intent = intent;
        this.primary = primary;
        this.chain = chain;
        this.reason = reason;
    }

    public IntentSignal getIntent() {
        return intent;
    }

    public ModelDefinition getPrimary() {
        return primary;
    }

    public List<ModelDefinition> getChain() {
        return chain;
    }

    public String getReason() {
        return reason;
    }

    /** 只保留能跑通的候选（理论上 resolveChain 已保证，这里再兜一层）。 */
    public List<ModelDefinition> usableChain() {
        return chain.stream().filter(m -> m != null).collect(Collectors.toList());
    }

    public String describeChain() {
        return chain.stream().map(ModelDefinition::getId).collect(Collectors.joining(" → "));
    }

    /** 供 ModelRegistry 做最终一致性校验用。 */
    public boolean isValid(ModelRegistry registry) {
        return primary != null && registry.contains(primary.getId());
    }
}
