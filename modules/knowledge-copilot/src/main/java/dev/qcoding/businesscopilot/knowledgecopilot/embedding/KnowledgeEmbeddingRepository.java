package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link KnowledgeChunkEmbedding} persistence backed by pgvector.
 *
 * <p>知识分片向量嵌入持久化接口。支持批量保存（含重建索引的 upsert 语义）、
 * 按 chunk 查询/删除、按文档级联删除，以及按余弦相似度检索最近的 chunk。</p>
 */
public interface KnowledgeEmbeddingRepository {

    /** Save a list of embeddings. Uses INSERT with DB-assigned IDs. */
    void saveAll(List<KnowledgeChunkEmbedding> embeddings);

    /** Delete the embedding for a single chunk. */
    int deleteByChunkId(Long chunkId);

    /** Find the embedding for a single chunk. */
    Optional<KnowledgeChunkEmbedding> findByChunkId(Long chunkId);

    /** Delete all embeddings for chunks belonging to a document. */
    int deleteByDocumentId(Long documentId);

    /**
     * Find the top-K most similar chunks to the given embedding vector,
     * limited to chunks from <strong>enabled</strong> documents only,
     * with a minimum cosine similarity threshold.
     *
     * <p>使用 pgvector 的 {@code <=>} 余弦距离操作符进行相似度检索。
     * 只检索 enabled=true 的文档的分片，且相似度低于 minSimilarity 的分片不返回。</p>
     *
     * @param embedding     the query embedding vector
     * @param topK          maximum number of results to return
     * @param minSimilarity minimum cosine similarity (0.0 to 1.0) a chunk must have to be included
     * @return list of matching chunk IDs with their similarity scores, ordered by similarity descending
     */
    List<SimilaritySearchResult> findSimilarChunks(float[] embedding, int topK, double minSimilarity);

    /**
     * A single similarity search result — a chunk ID and its cosine similarity score.
     */
    record SimilaritySearchResult(Long chunkId, double similarity) {
    }
}
