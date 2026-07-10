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
        });
    }
}
