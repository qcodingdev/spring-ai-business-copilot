package dev.qcoding.businesscopilot.knowledgecopilot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Knowledge Copilot module.
 *
 * <p>Knowledge Copilot 模块级配置。控制模块开关、上传文档大小上限、检索召回参数和
 * embedding 模型信息。分片参数（chunkSize / chunkOverlap）由
 * {@link dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingProperties} 单独管理。</p>
 *
 * <p>embedding 维度必须与所选 embedding 模型实际输出维度一致，并和 V4 迁移中
 * knowledge_chunk_embeddings.embedding 列维度一致，否则向量写入或检索会因维度不匹配而失败。</p>
 *
 * @param enabled            whether the Knowledge Copilot feature is active
 * @param maxDocumentSize    maximum accepted document size in bytes
 * @param topK               number of chunks retrieved per question
 * @param minSimilarity      minimum cosine similarity for a chunk to enter context
 * @param embeddingModelName embedding model name recorded for traceability
 * @param embeddingDimension embedding vector dimension; must match the embedding model and the V4 vector column
 */
@ConfigurationProperties(prefix = "business-copilot.knowledge")
public record KnowledgeCopilotProperties(
        boolean enabled,
        long maxDocumentSize,
        int topK,
        double minSimilarity,
        String embeddingModelName,
        int embeddingDimension) {

    /** Defaults aligned with the V2 MVP boundary. */
    public KnowledgeCopilotProperties {
        if (maxDocumentSize <= 0) {
            // 默认 2MB，超过该大小的单个文档将被拒绝
            maxDocumentSize = 2L * 1024 * 1024;
        }
        if (topK <= 0) {
            // 默认召回 top 5
            topK = 5;
        }
        if (minSimilarity <= 0) {
            // 默认最低相似度阈值 0.70，低于此值的片段不进入答案上下文
            minSimilarity = 0.70d;
        }
        if (embeddingModelName == null || embeddingModelName.isBlank()) {
            // 默认 embedding 模型名，需与实际配置的 Spring AI embedding 模型一致
            embeddingModelName = "text-embedding-3-small";
        }
        if (embeddingDimension <= 0) {
            // 默认维度 1536，对应 text-embedding-3-small。
            // 该值必须与 V4 迁移的 embedding 列维度以及实际 embedding 模型输出维度一致。
            embeddingDimension = 1536;
        }
    }
}
