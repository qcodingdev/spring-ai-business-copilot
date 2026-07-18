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
        java.util.UUID logicalDocumentId,
        int version,
        String title,
        int chunkCount,
        boolean enabled,
        boolean indexed,
        Long indexJobId,
        String indexStatus) {

    public DocumentUploadResponse(Long documentId, String title, int chunkCount,
                                  boolean enabled, boolean indexed) {
        this(documentId, null, 1, title, chunkCount, enabled, indexed,
                null, indexed ? "INDEXED" : "PENDING");
    }
}
