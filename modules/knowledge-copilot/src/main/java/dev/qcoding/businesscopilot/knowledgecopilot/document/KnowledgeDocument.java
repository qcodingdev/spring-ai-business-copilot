package dev.qcoding.businesscopilot.knowledgecopilot.document;

import java.time.Instant;

/**
 * Domain model for a knowledge document.
 *
 * <p>知识文档元数据。记录文档标题、来源、分类、内容哈希和启用状态。
 * 不存储完整文档正文——正文由分片（{@link dev.qcoding.businesscopilot.knowledgecopilot.chunking.ParsedSection}）承载。</p>
 *
 * @param id           primary key, assigned by DB
 * @param title        document title
 * @param sourceType   how the document was added, e.g. "upload" or "sample"
 * @param sourceName   original file name or source identifier
 * @param category     business category tag
 * @param contentHash  SHA-256 hex of the raw content, used for deduplication
 * @param enabled      whether this document's chunks participate in retrieval
 * @param createdAt    creation timestamp
 * @param updatedAt    last modification timestamp
 */
public record KnowledgeDocument(
        Long id,
        String title,
        String sourceType,
        String sourceName,
        String category,
        String contentHash,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        java.util.UUID logicalDocumentId,
        int versionNo,
        boolean currentVersion,
        String indexStatus,
        String indexErrorCategory,
        String contentType,
        String ownerActorId,
        KnowledgeVisibilityScope visibilityScope,
        boolean systemManaged) {

    public KnowledgeDocument(Long id, String title, String sourceType, String sourceName,
                             String category, String contentHash, boolean enabled,
                             Instant createdAt, Instant updatedAt) {
        this(id, title, sourceType, sourceName, category, contentHash, enabled,
                createdAt, updatedAt, null, 1, true,
                enabled ? "INDEXED" : "PENDING", null, "text/plain", null,
                KnowledgeVisibilityScope.ALL, false);
    }

    public KnowledgeDocument(Long id, String title, String sourceType, String sourceName,
                             String category, String contentHash, boolean enabled,
                             Instant createdAt, Instant updatedAt, java.util.UUID logicalDocumentId,
                             int versionNo, boolean currentVersion, String indexStatus,
                             String indexErrorCategory, String contentType, String ownerActorId) {
        this(id, title, sourceType, sourceName, category, contentHash, enabled,
                createdAt, updatedAt, logicalDocumentId, versionNo, currentVersion,
                indexStatus, indexErrorCategory, contentType, ownerActorId,
                KnowledgeVisibilityScope.ALL, false);
    }
}
