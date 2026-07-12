package dev.qcoding.businesscopilot.resumecopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeAssessmentMapper;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeJobMapper;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResumeCopilotAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ResumeCopilotAutoConfiguration.class))
            .withBean(AiChatService.class, () -> mock(AiChatService.class))
            .withBean(PromptTemplateService.class, PromptTemplateService::new)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ResumeJobMapper.class, () -> mock(ResumeJobMapper.class))
            .withBean(ResumeAssessmentMapper.class, () -> mock(ResumeAssessmentMapper.class));

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
                });
    }
}
