package dev.qcoding.businesscopilot.aicore;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the ai-core boundary.
 *
 * <p>启用组件扫描和属性绑定。当 chat model 未配置时，应用仍可启动，
 * 但 {@link AiChatService} 调用会给出清晰错误。</p>
 */
@Configuration
@ComponentScan
public class AiCoreAutoConfiguration {

    @ConfigurationProperties(prefix = "business-copilot.ai-core")
    @org.springframework.context.annotation.Bean
    public AiModelProperties aiModelProperties() {
        return new AiModelProperties(null, false, 0);
    }
}
