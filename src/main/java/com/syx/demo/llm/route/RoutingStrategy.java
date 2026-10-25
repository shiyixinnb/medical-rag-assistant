package com.syx.demo.llm.route;

import com.syx.demo.llm.model.ModelDefinition;

/**
 * 路由策略。抽成接口的理由是真实的：不同医院对"贵模型什么时候用"的容忍度完全不同 ——
 * 三甲急诊科愿意让所有分诊问题都走推理模型，社区门诊则希望只有明确的多因素问题才走。
 * 所以策略必须可替换：{@code app.llm.routing.enabled} 只能整体开关能力路由，
 * 想换整套算法时，自己实现一个 {@code RoutingStrategy} 并标注 {@code @Primary} 即可顶掉
 * {@link CapabilityRoutingStrategy}，调用方（ChatFacade）不需要任何改动。
 */
public interface RoutingStrategy {

    /**
     * 根据意图为本轮问题选出主模型与降级链。
     *
     * @param intent 意图判定结果
     * @param question 原始问题（有些策略会看长度/语言等特征）
     */
    RoutingDecision route(IntentSignal intent, String question);

    /** 策略名，用于可观测性输出。 */
    String name();

    /** 便捷方法：显式指定模型时的降级链展开。 */
    RoutingDecision explicit(ModelDefinition model, IntentSignal intent);
}
