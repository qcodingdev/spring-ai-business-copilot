package dev.qcoding.businesscopilot.aicore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiCoreAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiCoreAutoConfiguration.class));

    @Test
    void registersCoreServicesWithoutAConfiguredModel() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiModelProperties.class);
            assertThat(context).hasSingleBean(AiChatService.class);
            assertThat(context).hasSingleBean(AiEmbeddingService.class);
            assertThat(context).hasSingleBean(PromptTemplateService.class);
            assertThat(context).hasSingleBean(AiResilienceProperties.class);
            assertThat(context).hasSingleBean(AiCallCoordinator.class);
            assertThat(context).hasSingleBean(AiCallMetrics.class);
        });
    }

    @Test
    void bindsImmutableModelAndResilienceProperties() {
        contextRunner
                .withPropertyValues(
                        "business-copilot.ai-core.provider-name=openai-compatible",
                        "business-copilot.ai-core.model-name=release-model",
                        "business-copilot.ai-core.max-prompt-input-chars=2400",
                        "business-copilot.ai-core.resilience.max-concurrent-calls=6",
                        "business-copilot.ai-core.resilience.acquire-timeout=3s")
                .run(context -> {
                    AiModelProperties model = context.getBean(AiModelProperties.class);
                    AiResilienceProperties resilience = context.getBean(AiResilienceProperties.class);

                    assertThat(model.providerName()).isEqualTo("openai-compatible");
                    assertThat(model.modelName()).isEqualTo("release-model");
                    assertThat(model.maxPromptInputChars()).isEqualTo(2400);
                    assertThat(resilience.maxConcurrentCalls()).isEqualTo(6);
                    assertThat(resilience.acquireTimeout()).hasSeconds(3);
                });
    }
}
