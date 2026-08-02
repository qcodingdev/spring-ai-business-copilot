package dev.qcoding.businesscopilot.supportcopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotAutoConfiguration;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeRetrievalService;
import dev.qcoding.businesscopilot.supportcopilot.audit.JdbcSupportAuditRepository;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditRepository;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationService;
import dev.qcoding.businesscopilot.supportcopilot.draft.JdbcSupportReplyDraftRepository;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftConfirmationService;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftGuardrailService;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftService;
import dev.qcoding.businesscopilot.supportcopilot.draft.SupportReplyDraftRepository;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.FallbackSupportKnowledgeRetriever;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.KnowledgeCopilotSupportKnowledgeRetriever;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeRetriever;
import dev.qcoding.businesscopilot.supportcopilot.ticket.JdbcSupportTicketRepository;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import dev.qcoding.businesscopilot.supportcopilot.ticket.TicketAnalysisService;
import dev.qcoding.businesscopilot.supportcopilot.web.SupportCopilotController;
import dev.qcoding.businesscopilot.supportcopilot.web.SupportEnterpriseController;
import dev.qcoding.businesscopilot.supportcopilot.integration.RestSupportExternalAdapter;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportEnterpriseService;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportExternalAdapter;
import dev.qcoding.businesscopilot.supportcopilot.queue.SupportQueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Auto-configuration for the Support Copilot module.
 *
 * <p>Support Copilot 自动装配。注册工单分类、知识检索、草稿生成、人审状态流转、
 * 控制器和审计服务，不依赖宿主应用扫描项目根包。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(KnowledgeCopilotAutoConfiguration.class)
@ConditionalOnProperty(prefix = "business-copilot.support-copilot", name = "enabled", havingValue = "true")
public class SupportCopilotAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.support-copilot")
    public SupportCopilotProperties supportCopilotProperties() {
        return new SupportCopilotProperties(false, 0, 0, null, false, 0);
    }

    // ── Ticket beans ────────────────────────────────────────────

    @Bean
    public SupportTicketRepository supportTicketRepository(JdbcTemplate jdbcTemplate,
                                                            SensitiveTextMasker sensitiveTextMasker) {
        return new JdbcSupportTicketRepository(jdbcTemplate, sensitiveTextMasker);
    }

    // ── Reply draft beans ───────────────────────────────────────

    @Bean
    public SupportReplyDraftRepository supportReplyDraftRepository(JdbcTemplate jdbcTemplate,
                                                                    SensitiveTextMasker sensitiveTextMasker) {
        return new JdbcSupportReplyDraftRepository(jdbcTemplate, sensitiveTextMasker);
    }

    // ── Audit beans ─────────────────────────────────────────────

    @Bean
    public SupportAuditRepository supportAuditRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSupportAuditRepository(jdbcTemplate);
    }

    @Bean
    public SupportAuditService supportAuditService(SupportAuditRepository repository) {
        return new SupportAuditService(repository);
    }

    // ── Classification beans ──────────────────────────────────────

    @Bean
    public TicketClassificationService ticketClassificationService(
            dev.qcoding.businesscopilot.aicore.AiChatService aiChatService,
            dev.qcoding.businesscopilot.aicore.PromptTemplateService promptTemplateService,
            SensitiveTextMasker sensitiveTextMasker,
            SupportCopilotProperties properties) {
        return new TicketClassificationService(aiChatService, promptTemplateService,
                sensitiveTextMasker, properties);
    }

    // ── Draft generation beans ───────────────────────────────────

    @Bean
    public ReplyDraftGuardrailService replyDraftGuardrailService(SupportCopilotProperties properties) {
        return new ReplyDraftGuardrailService(properties);
    }

    @Bean
    public ReplyDraftService replyDraftService(
            dev.qcoding.businesscopilot.aicore.AiChatService aiChatService,
            dev.qcoding.businesscopilot.aicore.PromptTemplateService promptTemplateService,
            SensitiveTextMasker sensitiveTextMasker,
            ReplyDraftGuardrailService guardrailService,
            SupportReplyDraftRepository draftRepository,
            SupportCopilotProperties properties,
            CurrentActorProvider actorProvider,
            ConfirmationTokenService tokenService) {
        return new ReplyDraftService(aiChatService, promptTemplateService,
                sensitiveTextMasker, guardrailService, draftRepository, properties,
                actorProvider, tokenService);
    }

    @Bean
    public ReplyDraftConfirmationService replyDraftConfirmationService(
            SupportReplyDraftRepository draftRepository,
            SupportTicketRepository ticketRepository,
            SupportAuditService auditService,
            CurrentActorProvider actorProvider,
            ObjectAccessPolicy accessPolicy,
            ConfirmationTokenService tokenService,
            SensitiveTextMasker sensitiveTextMasker) {
        return new ReplyDraftConfirmationService(
                draftRepository, ticketRepository, auditService,
                actorProvider, accessPolicy, tokenService, sensitiveTextMasker);
    }

    // ── Knowledge retriever ──────────────────────────────────────────────

    @Bean
    @ConditionalOnBean({KnowledgeRetrievalService.class, KnowledgeDocumentRepository.class})
    public SupportKnowledgeRetriever knowledgeCopilotSupportKnowledgeRetriever(
            KnowledgeRetrievalService retrievalService,
            KnowledgeDocumentRepository documentRepository) {
        return new KnowledgeCopilotSupportKnowledgeRetriever(retrievalService, documentRepository);
    }

    @Bean
    @ConditionalOnMissingBean(SupportKnowledgeRetriever.class)
    public SupportKnowledgeRetriever supportKnowledgeRetriever() {
        return new FallbackSupportKnowledgeRetriever();
    }

    // ── Ticket analysis orchestrator ──────────────────────────────

    @Bean
    public TicketAnalysisService ticketAnalysisService(
            TicketClassificationService classificationService,
            SupportKnowledgeRetriever knowledgeRetriever,
            ReplyDraftService draftService,
            SupportTicketRepository ticketRepository,
            SupportAuditService auditService,
            SensitiveTextMasker sensitiveTextMasker,
            SupportCopilotProperties properties,
            CurrentActorProvider actorProvider) {
        return new TicketAnalysisService(classificationService, knowledgeRetriever,
                draftService, ticketRepository, auditService, sensitiveTextMasker,
                properties, actorProvider);
    }

    @Bean
    public SupportQueueService supportQueueService(
            JdbcTemplate jdbcTemplate,
            CurrentActorProvider actorProvider,
            SupportAuditService auditService) {
        return new SupportQueueService(jdbcTemplate, actorProvider, auditService);
    }

    @Bean
    public RestSupportExternalAdapter restSupportExternalAdapter(
            ExternalSecretResolver secretResolver,
            ExternalHttpClientFactory clientFactory) {
        return new RestSupportExternalAdapter(clientFactory, secretResolver);
    }

    @Bean
    public SupportEnterpriseService supportEnterpriseService(
            JdbcTemplate jdbcTemplate,
            SupportTicketRepository ticketRepository,
            List<SupportExternalAdapter> adapters,
            CurrentActorProvider actorProvider,
            ConfirmationTokenService tokenService,
            ExternalSecretResolver secretResolver,
            SensitiveTextMasker sensitiveTextMasker,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy) {
        return new SupportEnterpriseService(
                jdbcTemplate, ticketRepository, adapters, actorProvider, tokenService,
                secretResolver, sensitiveTextMasker, objectMapper, endpointPolicy);
    }

    @Bean
    @ConditionalOnMissingBean(SupportEnterpriseController.class)
    public SupportEnterpriseController supportEnterpriseController(SupportEnterpriseService service) {
        return new SupportEnterpriseController(service);
    }

    @Bean
    @ConditionalOnMissingBean(SupportCopilotController.class)
    public SupportCopilotController supportCopilotController(
            TicketAnalysisService analysisService,
            ReplyDraftConfirmationService confirmationService,
            SupportAuditService auditService,
            SupportQueueService queueService) {
        return new SupportCopilotController(
                analysisService, confirmationService, auditService, queueService);
    }
}
