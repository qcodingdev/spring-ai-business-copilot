package dev.qcoding.businesscopilot.knowledgecopilot.document;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.documentprocessing.DocumentFormat;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
import dev.qcoding.businesscopilot.documentprocessing.ExtractedDocument;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingService;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ParsedSection;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJob;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJobRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexingService;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 带版本管理的受限文档接入，并使用持久化异步索引任务。 */
public class DocumentUploadService {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final MarkdownDocumentParser markdownParser;
    private final TextDocumentParser textParser;
    private final ChunkingService chunkingService;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final KnowledgeCopilotProperties properties;
    private final KnowledgeEmbeddingRepository embeddingRepository;
    private final DocumentTextExtractor documentTextExtractor;
    private final KnowledgeIndexingService indexingService;
    private final KnowledgeIndexJobRepository indexJobRepository;
    private final CurrentActorProvider actorProvider;

    public DocumentUploadService(KnowledgeDocumentRepository documentRepository,
                                 KnowledgeChunkRepository chunkRepository,
                                 MarkdownDocumentParser markdownParser,
                                 TextDocumentParser textParser,
                                 ChunkingService chunkingService,
                                 SensitiveTextMasker sensitiveTextMasker,
                                 KnowledgeCopilotProperties properties,
                                 KnowledgeEmbeddingRepository embeddingRepository,
                                 DocumentTextExtractor documentTextExtractor,
                                 KnowledgeIndexingService indexingService,
                                 KnowledgeIndexJobRepository indexJobRepository,
                                 CurrentActorProvider actorProvider) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.markdownParser = markdownParser;
        this.textParser = textParser;
        this.chunkingService = chunkingService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.properties = properties;
        this.embeddingRepository = embeddingRepository;
        this.documentTextExtractor = documentTextExtractor;
        this.indexingService = indexingService;
        this.indexJobRepository = indexJobRepository;
        this.actorProvider = actorProvider;
    }

    @Transactional
    public DocumentUploadResponse upload(DocumentUploadRequest request) {
        byte[] content = request.content() == null
                ? new byte[0] : request.content().getBytes(StandardCharsets.UTF_8);
        return ingest(request.fileName(), contentType(request.fileName()), content,
                request.category(), request.logicalDocumentId());
    }

    @Transactional
    public DocumentUploadResponse uploadFile(String fileName, String contentType, byte[] content,
                                             String category, UUID logicalDocumentId) {
        return ingest(fileName, contentType, content, category, logicalDocumentId);
    }

    private DocumentUploadResponse ingest(String fileName, String contentType, byte[] bytes,
                                          String category, UUID requestedLogicalId) {
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (bytes != null && bytes.length > properties.maxDocumentSize()) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
        }
        ExtractedDocument extracted = documentTextExtractor.extract(fileName, contentType, bytes);
        String content = extracted.text();
        String contentHash = sha256Hex(content);
        if (documentRepository.existsByContentHash(contentHash)) {
            throw new BusinessException(ErrorCode.DOCUMENT_DUPLICATE);
        }

        List<ParsedSection> sections = parse(extracted.format(), content);
        if (sections.isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY);
        }

        UUID logicalDocumentId = requestedLogicalId == null ? UUID.randomUUID() : requestedLogicalId;
        int version = documentRepository.nextVersion(logicalDocumentId);
        if (version > 1) {
            documentRepository.supersedeCurrent(logicalDocumentId);
        }
        KnowledgeDocument document = new KnowledgeDocument(
                null, deriveTitle(fileName), "upload", fileName, category,
                contentHash, false, null, null,
                logicalDocumentId, version, true, "PENDING", null,
                contentType, actor.actorId());
        Long documentId = documentRepository.save(document);

        List<KnowledgeChunk> chunks = chunkingService.chunk(documentId, sections).stream()
                .map(this::maskChunk)
                .toList();
        chunkRepository.saveAll(chunks);
        KnowledgeIndexJob job = indexingService.enqueue(documentId);
        return new DocumentUploadResponse(
                documentId, logicalDocumentId, version, deriveTitle(fileName),
                chunks.size(), false, false, job.id(), job.status().name());
    }

    /** 创建新的索引任务，调用方可查询持久化任务状态。 */
    public KnowledgeIndexJob reindex(Long documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND));
        requireOwner(document);
        if (chunkRepository.findByDocumentId(documentId).isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY);
        }
        return indexingService.enqueue(documentId);
    }

    public KnowledgeIndexJob indexJob(Long jobId) {
        KnowledgeIndexJob job = indexJobRepository.findById(jobId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND));
        KnowledgeDocument document = documentRepository.findById(job.documentId()).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND));
        requireOwner(document);
        return job;
    }

    public boolean updateEnabled(Long documentId, boolean enabled) {
        KnowledgeDocument document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return false;
        }
        requireOwner(document);
        if (enabled && (!document.currentVersion() || !embeddingRepository.existsByDocumentId(documentId))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "只有已完成索引的当前文档版本可以启用。");
        }
        return documentRepository.updateEnabled(documentId, enabled);
    }

    @Transactional
    public boolean delete(Long documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return false;
        }
        requireOwner(document);
        boolean deleted = documentRepository.deleteById(documentId, document.ownerActorId());
        if (deleted && document.currentVersion()) {
            documentRepository.promoteLatestVersion(document.logicalDocumentId());
        }
        return deleted;
    }

    private void requireOwner(KnowledgeDocument document) {
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()
                || (!actor.actorId().equals(document.ownerActorId()) && !actor.hasRole(BusinessRole.ADMIN))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private KnowledgeChunk maskChunk(KnowledgeChunk chunk) {
        return new KnowledgeChunk(
                chunk.id(), chunk.documentId(), chunk.sectionTitle(), chunk.chunkIndex(),
                sensitiveTextMasker.mask(chunk.content()),
                sensitiveTextMasker.mask(chunk.contentPreview()),
                chunk.tokenCount(), chunk.createdAt());
    }

    private List<ParsedSection> parse(DocumentFormat format, String content) {
        return format == DocumentFormat.MARKDOWN
                ? markdownParser.parse(content) : textParser.parse(content);
    }

    private String deriveTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String contentType(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".md") || normalized.endsWith(".markdown")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    private String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }
}
