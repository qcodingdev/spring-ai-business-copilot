package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import java.time.Instant;

/**
 * 存储在 pgvector 中的单个知识分片向量领域模型。
 *
 * <p>知识分片向量嵌入。每条记录对应一个 {@code knowledge_chunks} 行的
 * embedding 向量及其元数据。{@code embedding} 是浮点数组，维度由
 * business-copilot.knowledge.embedding-dimension 配置并与模型实际输出一致。</p>
 *
 * @param id             数据库分配的主键
 * @param chunkId        指向 knowledge_chunks.id 的外键
 * @param embeddingModel 使用的向量模型名称
 * @param embedding      浮点数组形式的向量值
 * @param createdAt      数据库分配的创建时间
 */
public record KnowledgeChunkEmbedding(
        Long id,
        Long chunkId,
        String embeddingModel,
        float[] embedding,
        Instant createdAt) {
}
