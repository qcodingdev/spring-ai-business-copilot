package dev.qcoding.businesscopilot.knowledgecopilot.document;

/**
 * Response DTO for a successful document upload.
 *
 * <p>文档上传成功响应。返回文档 ID、标题、分片数和启用状态。</p>
 *
 * @param documentId the persisted document ID
 * @param title      document title (derived from filename or first heading)
 * @param chunkCount number of chunks created from this document
 * @param enabled    whether the document is enabled for retrieval
 * @param indexed    whether embeddings were created successfully
 */
public record DocumentUploadResponse(
        Long documentId,
        String title,
        int chunkCount,
        boolean enabled,
        boolean indexed) {
}
