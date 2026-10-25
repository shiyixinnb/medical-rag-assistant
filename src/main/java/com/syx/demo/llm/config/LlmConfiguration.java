package com.syx.demo.llm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 打开 {@code app.llm.*} 的配置绑定。
 *
 * <p>没有用 {@code @ConfigurationPropertiesScan}，而是显式 {@code @EnableConfigurationProperties} ——
 * 这样 "app.llm 全部配置项" 的入口就只有这一个文件，排查配置不生效时不用满项目找。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {
}
