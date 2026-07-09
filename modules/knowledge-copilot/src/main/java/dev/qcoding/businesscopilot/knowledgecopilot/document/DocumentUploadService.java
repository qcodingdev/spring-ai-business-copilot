package dev.qcoding.businesscopilot.knowledgecopilot.document;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingService;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ParsedSection;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Orchestrates the document upload flow: validate → hash → dedup → parse → chunk → mask → persist.
 *
 * <p>文档上传服务。完整流程：
 * <ol>
 *   <li>校验文件后缀（仅 .md/.markdown/.txt）。</li>
 *   <li>拒绝空文件和超过 max-document-size 的文件。</li>
 *   <li>计算 content_hash 做去重，命中则拒绝。</li>
 *   <li>按格式选择解析器，解析为 {@link ParsedSection}。</li>
 *   <li>分片，并对每个 chunk 的 content 做敏感信息脱敏后再入库。</li>
 *   <li>事务内保存 document 和 chunks。</li>
 * </ol>
 * </p>
 */
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".md", ".markdown", ".txt");

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final MarkdownDocumentParser markdownParser;
    private final TextDocumentParser textParser;
    private final ChunkingService chunkingService;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final KnowledgeCopilotProperties properties;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;

    public DocumentUploadService(KnowledgeDocumentRepository documentRepository,
                                 KnowledgeChunkRepository chunkRepository,
                                 MarkdownDocumentParser markdownParser,
                                 TextDocumentParser textParser,
                                 ChunkingService chunkingService,
                                 SensitiveTextMasker sensitiveTextMasker,
                                 KnowledgeCopilotProperties properties,
                                 KnowledgeEmbeddingService knowledgeEmbeddingService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.markdownParser = markdownParser;
        this.textParser = textParser;
        this.chunkingService = chunkingService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.properties = properties;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
    }

    /**
     * Upload and ingest a document.
     *
     * @param request validated upload request
     * @return response carrying the new document ID and chunk count
     * @throws BusinessException on validation, dedup, or persistence failures
     */
    @Transactional
    public DocumentUploadResponse upload(DocumentUploadRequest request) {
        // 1. 校验文件后缀
        String extension = extractExtension(request.fileName());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            log.warn("Rejected document with unsupported extension: {}", request.fileName());
            throw new BusinessException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                    "仅支持 .md、.markdown、.txt 文档，收到: " + request.fileName());
        }

        String content = request.content();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY, "文档内容为空");
        }

        // 2. 校验文件大小（按字节计）
        long sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > properties.maxDocumentSize()) {
            log.warn("Rejected oversized document: {} bytes, limit {}", sizeBytes, properties.maxDocumentSize());
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "文档大小 " + sizeBytes + " 字节超过上限 " + properties.maxDocumentSize());
        }

        // 3. 计算 content_hash 并去重
        String contentHash = sha256Hex(content);
        if (documentRepository.existsByContentHash(contentHash)) {
            log.info("Rejected duplicate document by content hash: {}", contentHash);
            throw new BusinessException(ErrorCode.DOCUMENT_DUPLICATE,
                    "内容完全相同的文档已存在");
        }

        // 4. 解析
        List<ParsedSection> sections = parse(extension, content);
        if (sections.isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY, "解析后未得到任何有效内容");
        }

        // 5. 保存文档元数据（先拿 documentId，分片需要引用）
        String title = deriveTitle(request.fileName(), sections);
        KnowledgeDocument document = new KnowledgeDocument(
                null, title, "upload", request.fileName(), request.category(),
                contentHash, true, null, null);
        Long documentId = documentRepository.save(document);

        // 6. 分片
        List<KnowledgeChunk> chunks = chunkingService.chunk(documentId, sections);

        // 7. 脱敏：chunk 内容入库前必须经过敏感信息处理
        List<KnowledgeChunk> maskedChunks = chunks.stream()
                .map(this::maskChunk)
                .toList();

        // 8. 保存分片
        chunkRepository.saveAll(maskedChunks);

        // 9. 获取已持久化的分片（含数据库生成的 id），生成并保存 embedding 向量
        List<KnowledgeChunk> savedChunks = chunkRepository.findByDocumentId(documentId);
        try {
            EmbeddingIndexResult embedResult = knowledgeEmbeddingService.indexChunks(documentId, savedChunks);
            log.info("Generated {} embeddings for document id={}, model={}, dim={}",
                    embedResult.chunkCount(), documentId, embedResult.modelName(), embedResult.dimension());
        } catch (dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException e) {
            log.warn("Embedding model not available; document id={} saved without embeddings", documentId);
        }

        log.info("Uploaded document id={} title='{}' chunks={}", documentId, title, maskedChunks.size());
        return new DocumentUploadResponse(documentId, title, maskedChunks.size(), true);
    }

    private KnowledgeChunk maskChunk(KnowledgeChunk chunk) {
        String maskedContent = sensitiveTextMasker.mask(chunk.content());
        String maskedPreview = sensitiveTextMasker.mask(chunk.contentPreview());
        return new KnowledgeChunk(
                chunk.id(),
                chunk.documentId(),
                chunk.sectionTitle(),
                chunk.chunkIndex(),
                maskedContent,
                maskedPreview,
                chunk.tokenCount(),
                chunk.createdAt());
    }

    private List<ParsedSection> parse(String extension, String content) {
        if (".txt".equals(extension)) {
            return textParser.parse(content);
        }
        return markdownParser.parse(content);
    }

    private String deriveTitle(String fileName, List<ParsedSection> sections) {
        // 去掉扩展名作为标题
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK, this should never happen
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
