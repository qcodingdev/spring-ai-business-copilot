package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import java.util.List;
import java.util.Optional;

/**
 * 基于 pgvector 的 {@link KnowledgeChunkEmbedding} 持久化接口。
 *
 * <p>知识分片向量嵌入持久化接口。支持批量保存（含重建索引的 upsert 语义）、
 * 按 chunk 查询/删除、按文档级联删除，以及按余弦相似度检索最近的 chunk。</p>
 */
public interface KnowledgeEmbeddingRepository {

    /** 批量保存向量，主键由数据库分配。 */
    void saveAll(List<KnowledgeChunkEmbedding> embeddings);

    /** 删除单个分片的向量。 */
    int deleteByChunkId(Long chunkId);

    /** 查询单个分片的向量。 */
    Optional<KnowledgeChunkEmbedding> findByChunkId(Long chunkId);

    /** 删除属于指定文档的全部分片向量。 */
    int deleteByDocumentId(Long documentId);

    /** 指定文档是否至少存在一个已持久化向量。 */
    boolean existsByDocumentId(Long documentId);

    /**
     * 查找与给定向量最相似的前 K 个分片，只返回已启用文档中
     * 模型和维度均与当前查询一致且达到最低余弦相似度的分片。
     *
     * <p>使用 pgvector 的 {@code <=>} 余弦距离操作符进行相似度检索。
     * 只检索 enabled=true 的文档的分片，且相似度低于 minSimilarity 的分片不返回。</p>
     *
     * @param embedding     查询向量
     * @param embeddingModel 当前查询使用的向量模型
     * @param topK          最大返回数量
     * @param minSimilarity 分片进入结果所需的最低余弦相似度
     * @return 按相似度降序排列的分片编号与分数
     */
    List<SimilaritySearchResult> findSimilarChunks(
            float[] embedding, String embeddingModel, int topK, double minSimilarity);

    /**
     * 单条相似度检索结果，包含分片编号和余弦相似度。
     */
    record SimilaritySearchResult(Long chunkId, double similarity) {
    }
}
