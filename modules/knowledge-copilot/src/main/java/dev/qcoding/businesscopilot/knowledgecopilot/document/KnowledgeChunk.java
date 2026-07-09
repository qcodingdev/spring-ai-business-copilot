package dev.qcoding.businesscopilot.knowledgecopilot.document;

import java.time.Instant;

/**
 * Domain model for a single knowledge chunk.
 *
 * <p>知识分片。一段文档正文的脱敏后文本及其元数据。{@code content} 存放脱敏后的完整文本，
 * {@code contentPreview} 存放短摘要，避免审计和列表暴露完整内容。</p>
 *
 * @param id             primary key, assigned by DB
 * @param documentId     owning document ID
 * @param sectionTitle   section heading the chunk belongs to
 * @param chunkIndex     global ordering of the chunk within the document
 * @param content        masked chunk text (full)
 * @param contentPreview short preview used for audit and listing
 * @param tokenCount     estimated token count of {@code content}
 * @param createdAt      creation timestamp, assigned by DB
 */
public record KnowledgeChunk(
        Long id,
        Long documentId,
        String sectionTitle,
        int chunkIndex,
        String content,
        String contentPreview,
        Integer tokenCount,
        Instant createdAt) {
}
