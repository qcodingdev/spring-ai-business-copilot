package dev.qcoding.businesscopilot.reportcopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceNormalizer;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceMapper;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceImportService;
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
import dev.qcoding.businesscopilot.reportcopilot.export.ReportMarkdownExportService;
import dev.qcoding.businesscopilot.reportcopilot.export.ReportHtmlExportService;
import dev.qcoding.businesscopilot.reportcopilot.export.ReportOfficeExportService;
import dev.qcoding.businesscopilot.reportcopilot.enterprise.ReportEnterpriseService;
import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.reportcopilot.web.ReportCopilotController;
import dev.qcoding.businesscopilot.reportcopilot.web.ReportEnterpriseController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for the Report Copilot module.
 *
 * <p>Registers source import and normalization, structured report generation, draft state
 * transitions, audit, confirmation, Markdown/HTML export, and the independent-host controller.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.report-copilot", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ReportCopilotProperties.class)
@EnableScheduling
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
                                                                            ReportSourceNormalizer normalizer,
                                                                            ReportCopilotProperties properties) {
        return new ReportRequestPreparationService(validator, sourceMapper, normalizer, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportSourceImportService reportSourceImportService(ObjectMapper objectMapper,
                                                               ReportSourceNormalizer normalizer,
                                                               ReportCopilotProperties properties) {
        return new ReportSourceImportService(objectMapper, normalizer, properties);
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
    public ReportDraftRepository reportDraftRepository(JdbcTemplate jdbcTemplate,
                                                        CurrentActorProvider actorProvider,
                                                        ConfirmationTokenService tokenService,
                                                        ObjectMapper objectMapper,
                                                        ReportCopilotProperties properties) {
        return new JdbcReportDraftRepository(
                jdbcTemplate, actorProvider, tokenService, objectMapper, properties.reviewSla());
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
                                                                         ReportAuditService auditService,
                                                                         CurrentActorProvider actorProvider,
                                                                         ObjectAccessPolicy accessPolicy,
                                                                         ConfirmationTokenService tokenService,
                                                                         ReportOutputSanitizer outputSanitizer) {
        return new ReportDraftConfirmationService(
                draftRepository, auditService, actorProvider, accessPolicy, tokenService, outputSanitizer);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportMarkdownExportService reportMarkdownExportService(ReportDraftRepository draftRepository,
                                                                   ReportCopilotProperties properties,
                                                                   ReportAuditService auditService,
                                                                   CurrentActorProvider actorProvider,
                                                                   ObjectAccessPolicy accessPolicy) {
        return new ReportMarkdownExportService(
                draftRepository, properties, auditService, actorProvider, accessPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportHtmlExportService reportHtmlExportService(ReportDraftRepository draftRepository,
                                                           ReportCopilotProperties properties,
                                                           ReportAuditService auditService,
                                                           CurrentActorProvider actorProvider,
                                                           ObjectAccessPolicy accessPolicy) {
        return new ReportHtmlExportService(
                draftRepository, properties, auditService, actorProvider, accessPolicy);
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

    @Bean
    @ConditionalOnMissingBean
    public ReportOfficeExportService reportOfficeExportService(
            ReportDraftRepository draftRepository,
            CurrentActorProvider actorProvider,
            ObjectAccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        return new ReportOfficeExportService(
                draftRepository, actorProvider, accessPolicy, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportEnterpriseService reportEnterpriseService(
            JdbcTemplate jdbcTemplate,
            ReportGenerationService generationService,
            CurrentActorProvider actorProvider,
            ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy,
            ExternalHttpClientFactory clientFactory) {
        return new ReportEnterpriseService(
                jdbcTemplate, generationService, actorProvider, secretResolver,
                objectMapper, endpointPolicy, clientFactory);
    }

    @Bean
    @ConditionalOnMissingBean(ReportEnterpriseController.class)
    public ReportEnterpriseController reportEnterpriseController(
            ReportEnterpriseService service,
            ReportOfficeExportService exportService) {
        return new ReportEnterpriseController(service, exportService);
    }

    @Bean
    @ConditionalOnMissingBean(ReportCopilotController.class)
    public ReportCopilotController reportCopilotController(
            ReportSourcePreviewService sourcePreviewService,
            ReportRequestPreparationService requestPreparationService,
            ReportSourceImportService sourceImportService,
            ReportGenerationService generationService,
            ReportDraftConfirmationService confirmationService,
            ReportMarkdownExportService markdownExportService,
            ReportHtmlExportService htmlExportService) {
        return new ReportCopilotController(sourcePreviewService, requestPreparationService,
                sourceImportService, generationService, confirmationService,
                markdownExportService, htmlExportService);
    }
}
