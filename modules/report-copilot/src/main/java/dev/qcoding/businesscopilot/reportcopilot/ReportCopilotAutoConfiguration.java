package dev.qcoding.businesscopilot.reportcopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceNormalizer;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceMapper;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportDataProvider;
import dev.qcoding.businesscopilot.reportcopilot.source.SampleReportDataProvider;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestValidator;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationOutputValidator;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportPromptContextFactory;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportOutputSanitizer;
import dev.qcoding.businesscopilot.reportcopilot.draft.JdbcReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftConfirmationService;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for the Report Copilot module.
 *
 * <p>The initial vertical slice exposes normalized fictional source data only. Report generation,
 * draft state transitions, and export are added in later slices.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.report-copilot", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ReportCopilotProperties.class)
public class ReportCopilotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SampleReportDataProvider sampleReportDataProvider() {
        return new SampleReportDataProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportSourceNormalizer reportSourceNormalizer(SensitiveTextMasker sensitiveTextMasker,
                                                         ReportCopilotProperties properties) {
        return new ReportSourceNormalizer(sensitiveTextMasker, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportSourcePreviewService reportSourcePreviewService(ReportDataProvider dataProvider,
                                                                  ReportSourceNormalizer normalizer) {
        return new ReportSourcePreviewService(dataProvider, normalizer);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportRequestValidator reportRequestValidator(ReportCopilotProperties properties) {
        return new ReportRequestValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportSourceMapper reportSourceMapper() {
        return new ReportSourceMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportRequestPreparationService reportRequestPreparationService(ReportRequestValidator validator,
                                                                            ReportSourceMapper sourceMapper,
                                                                            ReportSourceNormalizer normalizer) {
        return new ReportRequestPreparationService(validator, sourceMapper, normalizer);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportPromptContextFactory reportPromptContextFactory() {
        return new ReportPromptContextFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportGenerationOutputValidator reportGenerationOutputValidator() {
        return new ReportGenerationOutputValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportOutputSanitizer reportOutputSanitizer(SensitiveTextMasker sensitiveTextMasker) {
        return new ReportOutputSanitizer(sensitiveTextMasker);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportDraftRepository reportDraftRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcReportDraftRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportAuditService reportAuditService(JdbcTemplate jdbcTemplate) {
        return new ReportAuditService(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportDraftPersistenceService reportDraftPersistenceService(ReportDraftRepository draftRepository,
                                                                       ReportAuditService auditService,
                                                                       ReportCopilotProperties properties) {
        return new ReportDraftPersistenceService(draftRepository, auditService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportDraftConfirmationService reportDraftConfirmationService(ReportDraftRepository draftRepository,
                                                                         ReportAuditService auditService) {
        return new ReportDraftConfirmationService(draftRepository, auditService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportGenerationService reportGenerationService(ReportRequestPreparationService preparationService,
                                                           AiChatService aiChatService,
                                                           PromptTemplateService promptTemplateService,
                                                           ReportPromptContextFactory promptContextFactory,
                                                           ReportGenerationOutputValidator outputValidator,
                                                           ReportOutputSanitizer outputSanitizer,
                                                           ReportDraftPersistenceService draftPersistenceService) {
        return new ReportGenerationService(preparationService, aiChatService, promptTemplateService,
                promptContextFactory, outputValidator, outputSanitizer, draftPersistenceService);
    }
}
