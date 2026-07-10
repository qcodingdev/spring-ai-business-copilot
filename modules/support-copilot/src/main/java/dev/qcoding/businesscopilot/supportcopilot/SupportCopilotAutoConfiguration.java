package dev.qcoding.businesscopilot.supportcopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for the Support Copilot module.
 *
 * <p>Support Copilot 自动装配。注册模块配置、工单/草稿/审计仓库、脱敏器等组件。
 * 业务 service（分类、检索、草稿生成等）将在后续步骤补充。</p>
 */
@AutoConfiguration
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
            SupportCopilotProperties properties) {
        return new ReplyDraftService(aiChatService, promptTemplateService,
                sensitiveTextMasker, guardrailService, draftRepository, properties);
    }

    @Bean
    public ReplyDraftConfirmationService replyDraftConfirmationService(
            SupportReplyDraftRepository draftRepository,
            SupportTicketRepository ticketRepository,
            SupportAuditService auditService) {
        return new ReplyDraftConfirmationService(draftRepository, ticketRepository, auditService);
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
            SupportCopilotProperties properties) {
        return new TicketAnalysisService(classificationService, knowledgeRetriever,
                draftService, ticketRepository, auditService, sensitiveTextMasker, properties);
    }
}
