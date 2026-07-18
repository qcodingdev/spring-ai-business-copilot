package dev.qcoding.businesscopilot.resumecopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonsecurity.CommonSecurityAutoConfiguration;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import dev.qcoding.businesscopilot.resumecopilot.web.ResumeCopilotController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResumeCopilotAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CommonSecurityAutoConfiguration.class,
                    ResumeCopilotAutoConfiguration.class))
            .withBean(AiChatService.class, () -> mock(AiChatService.class))
            .withBean(PromptTemplateService.class, PromptTemplateService::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DocumentTextExtractor.class, () -> mock(DocumentTextExtractor.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test
    void remainsDisabledByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ResumePrivacySanitizer.class));
    }

    @Test
    void registersWorkflowWhenEnabled() {
        runner.withPropertyValues("business-copilot.resume-copilot.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ResumePrivacySanitizer.class);
                    assertThat(context).hasSingleBean(dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaService.class);
                    assertThat(context).hasSingleBean(dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService.class);
                    assertThat(context).hasSingleBean(ResumeCopilotController.class);
                });
    }
}
