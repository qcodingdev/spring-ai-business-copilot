package dev.qcoding.businesscopilot.reportcopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ReportCopilotAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReportCopilotAutoConfiguration.class))
            .withBean(SensitiveTextMasker.class, SensitiveTextMasker::new)
            .withBean(AiChatService.class, () -> org.mockito.Mockito.mock(AiChatService.class))
            .withBean(PromptTemplateService.class, PromptTemplateService::new)
            .withBean(JdbcTemplate.class, () -> org.mockito.Mockito.mock(JdbcTemplate.class));

    @Test
    void doesNotRegisterPreviewFeatureWhenDisabled() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(ReportSourcePreviewService.class));
    }

    @Test
    void registersPreviewFeatureWithConfiguredLimitsWhenEnabled() {
        contextRunner.withPropertyValues(
                        "business-copilot.report-copilot.enabled=true",
                        "business-copilot.report-copilot.max-source-count=4",
                        "business-copilot.report-copilot.allowed-report-types=TEAM_WEEKLY,BUSINESS_WEEKLY")
                .run(context -> {
                    assertThat(context).hasSingleBean(ReportSourcePreviewService.class);
                    assertThat(context).hasSingleBean(ReportRequestPreparationService.class);
                    assertThat(context.getBean(ReportCopilotProperties.class).maxSourceCount()).isEqualTo(4);
                    assertThat(context.getBean(ReportSourcePreviewService.class).preview().sources()).hasSize(4);
                });
    }
}
