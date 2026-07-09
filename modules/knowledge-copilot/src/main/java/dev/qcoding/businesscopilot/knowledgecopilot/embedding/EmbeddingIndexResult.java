package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

/**
 * Result of an embedding indexing operation for a single document.
 *
 * <p>embedding 索引结果：文档 ID、已索引分片数、使用的模型名称和向量维度。
 * 由 {@link KnowledgeEmbeddingService} 返回，供调用方记录和审计。</p>
 *
 * @param documentId the document that was indexed
 * @param chunkCount number of chunks indexed
 * @param modelName  embedding model used
 * @param dimension  embedding vector dimension
 */
public record EmbeddingIndexResult(
        Long documentId,
        int chunkCount,
        String modelName,
        int dimension) {
}
