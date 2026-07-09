package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

/**
 * A knowledge chunk enriched with its retrieval similarity score and embedding metadata.
 *
 * <p>检索结果：包含 chunk 的完整信息，以及向量检索的相似度分数和 embedding 模型名。
 * 由 {@link KnowledgeRetrievalService} 返回，供答案生成 service 使用。</p>
 *
 * @param chunk          the full knowledge chunk (id, documentId, content, etc.)
 * @param similarity     cosine similarity score between the query embedding and this chunk's embedding
 * @param embeddingModel name of the embedding model used for retrieval
 */
public record RetrievedKnowledgeChunk(
        dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk chunk,
        double similarity,
        String embeddingModel) {
}
