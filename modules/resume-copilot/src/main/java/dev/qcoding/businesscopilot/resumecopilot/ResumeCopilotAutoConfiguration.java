package dev.qcoding.businesscopilot.resumecopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentGuardrail;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import dev.qcoding.businesscopilot.resumecopilot.evidence.ResumeEvidenceService;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaGuardrail;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaService;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeAssessmentMapper;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeJobMapper;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeRepository;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.resume-copilot", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ResumeCopilotProperties.class)
public class ResumeCopilotAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public ResumePrivacySanitizer resumePrivacySanitizer(ResumeCopilotProperties properties) {
        return new ResumePrivacySanitizer(properties);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeRepository resumeRepository(ResumeJobMapper jobMapper, ResumeAssessmentMapper assessmentMapper,
                                             JdbcTemplate jdbcTemplate) {
        return new ResumeRepository(jobMapper, assessmentMapper, jdbcTemplate);
    }

    @Bean @ConditionalOnMissingBean
    public JobCriteriaGuardrail jobCriteriaGuardrail(ResumeCopilotProperties properties) {
        return new JobCriteriaGuardrail(properties);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeEvidenceService resumeEvidenceService(ResumeCopilotProperties properties) {
        return new ResumeEvidenceService(properties);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeAssessmentGuardrail resumeAssessmentGuardrail() {
        return new ResumeAssessmentGuardrail();
    }

    @Bean @ConditionalOnMissingBean
    public JobCriteriaService jobCriteriaService(ResumePrivacySanitizer sanitizer, AiChatService ai,
                                                 PromptTemplateService prompts, JobCriteriaGuardrail guardrail,
                                                 ResumeRepository repository, ResumeCopilotProperties properties) {
        return new JobCriteriaService(sanitizer, ai, prompts, guardrail, repository, properties);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeAssessmentService resumeAssessmentService(ResumePrivacySanitizer sanitizer,
                                                           ResumeEvidenceService evidenceService,
                                                           JobCriteriaService criteriaService,
                                                           ResumeAssessmentGuardrail guardrail,
                                                           ResumeRepository repository, AiChatService ai,
                                                           PromptTemplateService prompts,
                                                           ResumeCopilotProperties properties) {
        return new ResumeAssessmentService(sanitizer, evidenceService, criteriaService, guardrail, repository,
                ai, prompts, properties);
    }
}
