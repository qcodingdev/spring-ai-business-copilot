package dev.qcoding.businesscopilot.aicore;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
        return new AiModelProperties(null, false, 0);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptTemplateService promptTemplateService() {
        return new PromptTemplateService();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiChatService aiChatService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                       AiModelProperties properties) {
        return new AiChatService(chatClientBuilderProvider, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiEmbeddingService aiEmbeddingService(
            ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider,
            AiModelProperties properties) {
        return new AiEmbeddingService(embeddingModelProvider, properties);
    }
}
