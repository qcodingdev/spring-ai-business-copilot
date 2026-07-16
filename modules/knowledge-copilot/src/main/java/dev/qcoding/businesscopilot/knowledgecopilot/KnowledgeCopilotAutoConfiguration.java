package dev.qcoding.businesscopilot.knowledgecopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerService;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeQuestionService;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.JdbcKnowledgeQaAuditRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.KnowledgeAuditService;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.KnowledgeQaAuditRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingService;
import dev.qcoding.businesscopilot.knowledgecopilot.citation.CitationGuardrailService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.JdbcKnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.JdbcKnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.MarkdownDocumentParser;
import dev.qcoding.businesscopilot.knowledgecopilot.document.TextDocumentParser;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.JdbcKnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeRetrievalService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for the Knowledge Copilot module.
 *
 * <p>Knowledge Copilot 自动装配。注册文档解析、分片、embedding、检索、问答和审计服务。
 * Controller 的独立宿主装配将在 v1.2 继续收口。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeCopilotAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.knowledge")
    public KnowledgeCopilotProperties knowledgeCopilotProperties() {
        return new KnowledgeCopilotProperties(false, 0, 0, 0d, null, 0);
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.knowledge.chunking")
    public ChunkingProperties chunkingProperties() {
        return new ChunkingProperties(0, 0);
    }

    @Bean
    public KnowledgeDocumentRepository knowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeDocumentRepository(jdbcTemplate);
    }

    @Bean
    public KnowledgeChunkRepository knowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeChunkRepository(jdbcTemplate);
    }

    @Bean
    public MarkdownDocumentParser markdownDocumentParser() {
        return new MarkdownDocumentParser();
    }

    @Bean
    public TextDocumentParser textDocumentParser() {
        return new TextDocumentParser();
    }

    @Bean
    public ChunkingService chunkingService(ChunkingProperties chunkingProperties) {
        return new ChunkingService(chunkingProperties);
    }

    @Bean
    public KnowledgeEmbeddingRepository knowledgeEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);
    }

    @Bean
    public KnowledgeEmbeddingService knowledgeEmbeddingService(
            dev.qcoding.businesscopilot.aicore.AiEmbeddingService aiEmbeddingService,
            KnowledgeEmbeddingRepository embeddingRepository,
            KnowledgeCopilotProperties properties) {
        return new KnowledgeEmbeddingService(aiEmbeddingService, embeddingRepository, properties);
    }

    @Bean
    public DocumentUploadService documentUploadService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            MarkdownDocumentParser markdownParser,
            TextDocumentParser textParser,
            ChunkingService chunkingService,
            SensitiveTextMasker sensitiveTextMasker,
            KnowledgeCopilotProperties properties,
            KnowledgeEmbeddingService knowledgeEmbeddingService,
            KnowledgeEmbeddingRepository embeddingRepository) {
        return new DocumentUploadService(
                documentRepository, chunkRepository, markdownParser,
                textParser, chunkingService, sensitiveTextMasker, properties,
                knowledgeEmbeddingService, embeddingRepository);
    }

    // ── Retrieval & Q&A beans ──────────────────────────────────

    @Bean
    public KnowledgeRetrievalService knowledgeRetrievalService(
            dev.qcoding.businesscopilot.aicore.AiEmbeddingService aiEmbeddingService,
            KnowledgeEmbeddingRepository embeddingRepository,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeCopilotProperties properties) {
        return new KnowledgeRetrievalService(aiEmbeddingService, embeddingRepository, chunkRepository, properties);
    }

    @Bean
    public CitationGuardrailService citationGuardrailService() {
        return new CitationGuardrailService();
    }

    @Bean
    public KnowledgeAnswerService knowledgeAnswerService(
            dev.qcoding.businesscopilot.aicore.AiChatService aiChatService,
            dev.qcoding.businesscopilot.aicore.PromptTemplateService promptTemplateService,
            CitationGuardrailService citationGuardrailService,
            SensitiveTextMasker sensitiveTextMasker) {
        return new KnowledgeAnswerService(aiChatService, promptTemplateService,
                citationGuardrailService, sensitiveTextMasker);
    }

    @Bean
    public KnowledgeQuestionService knowledgeQuestionService(
            KnowledgeRetrievalService retrievalService,
            KnowledgeAnswerService answerService) {
        return new KnowledgeQuestionService(retrievalService, answerService);
    }

    // ── Audit beans ────────────────────────────────────────────

    @Bean
    public KnowledgeQaAuditRepository knowledgeQaAuditRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeQaAuditRepository(jdbcTemplate);
    }

    @Bean
    public KnowledgeAuditService knowledgeAuditService(KnowledgeQaAuditRepository repository) {
        return new KnowledgeAuditService(repository);
    }
}
