package dev.qcoding.businesscopilot.aicore;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Auto-configuration for the ai-core boundary.
 *
 * <p>显式注册 AI Core Bean 和属性绑定。当 chat model 未配置时，应用仍可启动，
 * 但 {@link AiChatService} 调用会给出清晰错误。</p>
 */
@AutoConfiguration
public class AiCoreAutoConfiguration {

    @ConfigurationProperties(prefix = "business-copilot.ai-core")
    @Bean
    @ConditionalOnMissingBean
    public AiModelProperties aiModelProperties() {
        return new AiModelProperties(null, null, false, 0);
    }

    @ConfigurationProperties(prefix = "business-copilot.ai-core.resilience")
    @Bean
    @ConditionalOnMissingBean
    public AiResilienceProperties aiResilienceProperties() {
        return new AiResilienceProperties(0, null, 0, 0, 0, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiCallMetrics aiCallMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider,
                                       ObjectProvider<AiUsageRecorder> usageRecorderProvider,
                                       AiModelProperties properties) {
        return new AiCallMetrics(meterRegistryProvider.getIfAvailable(), properties,
                usageRecorderProvider.getIfAvailable(() -> AiUsageRecorder.NO_OP));
    }

    @Bean
    @ConditionalOnMissingBean
    public AiCallCoordinator aiCallCoordinator(AiResilienceProperties properties,
                                               AiCallMetrics metrics) {
        return new AiCallCoordinator(properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptTemplateService promptTemplateService() {
        return new PromptTemplateService();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiChatService aiChatService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                       AiModelProperties properties,
                                       AiCallCoordinator coordinator) {
        return new AiChatService(chatClientBuilderProvider, properties, coordinator);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiEmbeddingService aiEmbeddingService(
            ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider,
            AiModelProperties properties,
            AiCallCoordinator coordinator) {
        return new AiEmbeddingService(embeddingModelProvider, properties, coordinator);
    }
}
