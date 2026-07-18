package dev.qcoding.businesscopilot.resumecopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentGuardrail;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import dev.qcoding.businesscopilot.resumecopilot.evidence.ResumeEvidenceService;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaGuardrail;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaService;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeRepository;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumeRetentionService;
import dev.qcoding.businesscopilot.resumecopilot.web.ResumeCopilotController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.resume-copilot", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ResumeCopilotProperties.class)
@EnableScheduling
public class ResumeCopilotAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public ResumePrivacySanitizer resumePrivacySanitizer(ResumeCopilotProperties properties) {
        return new ResumePrivacySanitizer(properties);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeRepository resumeRepository(JdbcTemplate jdbcTemplate) {
        return new ResumeRepository(jdbcTemplate);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeRetentionService resumeRetentionService(ResumeRepository repository) {
        return new ResumeRetentionService(repository);
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
                                                 ResumeRepository repository, ResumeCopilotProperties properties,
                                                 CurrentActorProvider actorProvider,
                                                 ObjectAccessPolicy accessPolicy,
                                                 ConfirmationTokenService tokenService,
                                                 ObjectMapper objectMapper,
                                                 DocumentTextExtractor documentTextExtractor) {
        return new JobCriteriaService(sanitizer, ai, prompts, guardrail, repository, properties,
                actorProvider, accessPolicy, tokenService, objectMapper, documentTextExtractor);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeAssessmentService resumeAssessmentService(ResumePrivacySanitizer sanitizer,
                                                           ResumeEvidenceService evidenceService,
                                                           JobCriteriaService criteriaService,
                                                           ResumeAssessmentGuardrail guardrail,
                                                           ResumeRepository repository, AiChatService ai,
                                                           PromptTemplateService prompts,
                                                           ResumeCopilotProperties properties,
                                                           CurrentActorProvider actorProvider,
                                                           ObjectAccessPolicy accessPolicy,
                                                           ConfirmationTokenService tokenService,
                                                           ObjectMapper objectMapper,
                                                           DocumentTextExtractor documentTextExtractor) {
        return new ResumeAssessmentService(sanitizer, evidenceService, criteriaService, guardrail, repository,
                ai, prompts, properties, actorProvider, accessPolicy, tokenService,
                objectMapper, documentTextExtractor);
    }

    @Bean @ConditionalOnMissingBean
    public ResumeCopilotController resumeCopilotController(JobCriteriaService criteriaService,
                                                           ResumeAssessmentService assessmentService) {
        return new ResumeCopilotController(criteriaService, assessmentService);
    }
}
