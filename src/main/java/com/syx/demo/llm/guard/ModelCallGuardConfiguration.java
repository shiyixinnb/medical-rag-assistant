package com.syx.demo.llm.guard;

import com.syx.demo.llm.config.LlmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 熔断后端的装配策略，由 {@code app.llm.breaker.backend} 决定：
 *
 * <table border="1">
 *   <tr><th>值</th><th>行为</th></tr>
 *   <tr><td>auto（默认）</td><td>类路径上有 Sentinel 就用它，没有就用自研滑动窗口</td></tr>
 *   <tr><td>sentinel</td><td>强制用 Sentinel。类路径上缺依赖时启动即失败 —— 这是刻意的，
 *       显式要求了却不能生效，比静默降级成另一个实现安全</td></tr>
 *   <tr><td>simple</td><td>强制用自研实现（医院内网禁止引入 Sentinel 时用）</td></tr>
 * </table>
 *
 * <p>auto 分支拆成"类存在"和"类不存在"两个互斥的嵌套配置类，是为了让判定在
 * <b>配置类解析阶段</b>完成。如果把判断写进 {@code @Bean} 方法体里用 {@code ClassUtils.isPresent}
 * 去判断，那个方法所在的类仍会被加载，Sentinel 缺失时照样可能抛 {@code NoClassDefFoundError}。
 */
@Configuration
public class ModelCallGuardConfiguration {

    /** 显式指定 simple。 */
    @Bean
    @ConditionalOnProperty(name = "app.llm.breaker.backend", havingValue = "simple")
    public ModelCallGuard simpleModelCallGuard(LlmProperties properties) {
        return new SimpleSlidingWindowGuard(properties.getBreaker());
    }

    /** 显式指定 sentinel。 */
    @Configuration
    @ConditionalOnProperty(name = "app.llm.breaker.backend", havingValue = "sentinel")
    static class ExplicitSentinelBackend {
        @Bean
        public ModelCallGuard sentinelModelCallGuard(LlmProperties properties) {
            return new SentinelModelCallGuard(properties.getBreaker());
        }
    }

    /** auto + Sentinel 在类路径上。 */
    @Configuration
    @ConditionalOnProperty(name = "app.llm.breaker.backend", havingValue = "auto", matchIfMissing = true)
    @ConditionalOnClass(name = "com.alibaba.csp.sentinel.SphU")
    static class AutoWithSentinel {
        @Bean
        public ModelCallGuard sentinelModelCallGuard(LlmProperties properties) {
            return new SentinelModelCallGuard(properties.getBreaker());
        }
    }

    /** auto + 没有 Sentinel：退回自研滑动窗口，保证功能不缺。 */
    @Configuration
    @ConditionalOnProperty(name = "app.llm.breaker.backend", havingValue = "auto", matchIfMissing = true)
    @ConditionalOnMissingClass("com.alibaba.csp.sentinel.SphU")
    static class AutoWithSimple {
        @Bean
        public ModelCallGuard simpleSlidingWindowGuard(LlmProperties properties) {
            return new SimpleSlidingWindowGuard(properties.getBreaker());
        }
    }
}
