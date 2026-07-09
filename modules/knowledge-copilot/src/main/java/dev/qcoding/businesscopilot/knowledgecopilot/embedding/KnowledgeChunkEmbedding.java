package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import java.time.Instant;

/**
 * Domain model for a single chunk embedding stored in pgvector.
 *
 * <p>知识分片向量嵌入。每条记录对应一个 {@code knowledge_chunks} 行的
 * embedding 向量及其元数据。{@code embedding} 是浮点数组，维度由
 * business-copilot.knowledge.embedding-dimension 配置并与 V4 迁移的
 * knowledge_chunk_embeddings.embedding 列类型一致。</p>
 *
 * @param id             primary key, assigned by DB
 * @param chunkId        foreign key to knowledge_chunks.id
 * @param embeddingModel name of the embedding model used
 * @param embedding      vector values as float array
 * @param createdAt      creation timestamp, assigned by DB
 */
public record KnowledgeChunkEmbedding(
        Long id,
        Long chunkId,
        String embeddingModel,
        float[] embedding,
        Instant createdAt) {
}
