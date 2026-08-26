package dev.qcoding.businesscopilot.knowledgecopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
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
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.JdbcKnowledgeIndexJobRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJobRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexLifecycleService;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexingService;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.JdbcKnowledgeFeedbackRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackService;
import dev.qcoding.businesscopilot.knowledgecopilot.web.KnowledgeCopilotController;
import dev.qcoding.businesscopilot.knowledgecopilot.web.KnowledgeSourceController;
import dev.qcoding.businesscopilot.knowledgecopilot.source.CloudKnowledgeSourceAdapter;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceAdapter;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceSyncService;
import dev.qcoding.businesscopilot.knowledgecopilot.source.MinioKnowledgeSourceAdapter;
import dev.qcoding.businesscopilot.knowledgecopilot.source.MountedDriveKnowledgeSourceAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Auto-configuration for the Knowledge Copilot module.
 *
 * <p>Knowledge Copilot 自动装配。注册文档解析、分片、异步索引、混合检索、
 * 问答、控制器和审计服务，不依赖宿主应用扫描项目根包。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.knowledge", name = "enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(KnowledgeCopilotProperties.class)
public class KnowledgeCopilotAutoConfiguration {

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
    public KnowledgeIndexJobRepository knowledgeIndexJobRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeIndexJobRepository(jdbcTemplate);
    }

    @Bean
    public KnowledgeIndexLifecycleService knowledgeIndexLifecycleService(
            KnowledgeIndexJobRepository jobRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeEmbeddingService embeddingService) {
        return new KnowledgeIndexLifecycleService(
                jobRepository, documentRepository, embeddingService);
    }

    @Bean
    public KnowledgeIndexingService knowledgeIndexingService(
            KnowledgeIndexJobRepository jobRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeEmbeddingService embeddingService,
            KnowledgeIndexLifecycleService lifecycleService) {
        return new KnowledgeIndexingService(
                jobRepository, documentRepository, chunkRepository, embeddingService, lifecycleService);
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
            DocumentTextExtractor documentTextExtractor,
            KnowledgeIndexingService indexingService,
            KnowledgeIndexJobRepository indexJobRepository,
            CurrentActorProvider actorProvider) {
        return new DocumentUploadService(
                documentRepository, chunkRepository, markdownParser,
                textParser, chunkingService, sensitiveTextMasker, properties,
                documentTextExtractor, indexingService, indexJobRepository, actorProvider);
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
            KnowledgeAnswerService answerService,
            SensitiveTextMasker sensitiveTextMasker) {
        return new KnowledgeQuestionService(retrievalService, answerService, sensitiveTextMasker);
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

    @Bean
    public KnowledgeFeedbackRepository knowledgeFeedbackRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeFeedbackRepository(jdbcTemplate);
    }

    @Bean
    public KnowledgeFeedbackService knowledgeFeedbackService(
            KnowledgeFeedbackRepository repository,
            CurrentActorProvider actorProvider,
            SensitiveTextMasker sensitiveTextMasker) {
        return new KnowledgeFeedbackService(repository, actorProvider, sensitiveTextMasker);
    }

    @Bean
    public MountedDriveKnowledgeSourceAdapter mountedDriveKnowledgeSourceAdapter(
            ExternalEndpointPolicy endpointPolicy) {
        return new MountedDriveKnowledgeSourceAdapter(
                endpointPolicy.properties().maxResponseBytes(),
                endpointPolicy.properties().maxItems());
    }

    @Bean
    public MinioKnowledgeSourceAdapter minioKnowledgeSourceAdapter(
            ExternalSecretResolver secretResolver, ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy) {
        return new MinioKnowledgeSourceAdapter(secretResolver, objectMapper, endpointPolicy);
    }

    @Bean
    public CloudKnowledgeSourceAdapter cloudKnowledgeSourceAdapter(
            ExternalSecretResolver secretResolver,
            ExternalHttpClientFactory clientFactory) {
        return new CloudKnowledgeSourceAdapter(clientFactory, secretResolver);
    }

    @Bean
    public KnowledgeSourceSyncService knowledgeSourceSyncService(
            JdbcTemplate jdbcTemplate,
            List<KnowledgeSourceAdapter> adapters,
            DocumentUploadService uploadService,
            CurrentActorProvider actorProvider,
            ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy,
            KnowledgeCopilotProperties properties) {
        return new KnowledgeSourceSyncService(
                jdbcTemplate, adapters, uploadService, actorProvider, secretResolver,
                objectMapper, endpointPolicy, properties.indexStaleAfter());
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeSourceController.class)
    public KnowledgeSourceController knowledgeSourceController(KnowledgeSourceSyncService service) {
        return new KnowledgeSourceController(service);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeCopilotController.class)
    public KnowledgeCopilotController knowledgeCopilotController(
            DocumentUploadService documentUploadService,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeQuestionService questionService,
            KnowledgeAuditService auditService,
            KnowledgeFeedbackService feedbackService) {
        return new KnowledgeCopilotController(
                documentUploadService, documentRepository, questionService, auditService, feedbackService);
    }
}
