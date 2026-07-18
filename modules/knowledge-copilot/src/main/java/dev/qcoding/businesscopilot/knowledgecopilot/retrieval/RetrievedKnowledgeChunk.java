package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

/**
 * 携带检索相似度与向量元数据的知识分片。
 *
 * <p>检索结果：包含 chunk 的完整信息，以及向量检索的相似度分数和 embedding 模型名。
 * 由 {@link KnowledgeRetrievalService} 返回，供答案生成 service 使用。</p>
 *
 * @param chunk          完整知识分片
 * @param similarity     查询向量与分片向量之间的余弦相似度
 * @param embeddingModel 检索所用向量模型名称
 */
public record RetrievedKnowledgeChunk(
        dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk chunk,
        double similarity,
        String embeddingModel) {
}
