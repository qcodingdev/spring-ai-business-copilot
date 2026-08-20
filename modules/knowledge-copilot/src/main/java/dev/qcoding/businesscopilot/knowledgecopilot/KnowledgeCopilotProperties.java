package dev.qcoding.businesscopilot.knowledgecopilot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * Knowledge Copilot 模块配置。
 *
 * <p>Knowledge Copilot 模块级配置。控制模块开关、上传文档大小上限、检索召回参数和
 * embedding 模型信息。分片参数（chunkSize / chunkOverlap）由
 * {@link dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingProperties} 单独管理。</p>
 *
 * <p>向量维度必须与所选向量模型实际输出维度一致。V17 起数据库列支持可变维度，
 * 但同一次检索使用的文档向量仍必须保持同一模型和维度。</p>
 *
 * @param enabled            是否启用 Knowledge Copilot
 * @param maxDocumentSize    可接受的文档最大字节数
 * @param topK               每个问题召回的分片数
 * @param minSimilarity      分片进入上下文所需的最低余弦相似度
 * @param embeddingModelName 用于追踪的向量模型名称
 * @param embeddingDimension 向量维度，必须与模型实际输出一致
 * @param indexStaleAfter    索引任务进入可人工恢复状态前的最长无更新时间
 */
@ConfigurationProperties(prefix = "business-copilot.knowledge")
public record KnowledgeCopilotProperties(
        boolean enabled,
        long maxDocumentSize,
        int topK,
        double minSimilarity,
        String embeddingModelName,
        int embeddingDimension,
        Duration indexStaleAfter) {

    public KnowledgeCopilotProperties(boolean enabled, long maxDocumentSize, int topK,
                                      double minSimilarity, String embeddingModelName,
                                      int embeddingDimension) {
        this(enabled, maxDocumentSize, topK, minSimilarity, embeddingModelName,
                embeddingDimension, Duration.ofMinutes(15));
    }

    /** 与 2.0 边界一致的保守默认值。 */
    @ConstructorBinding
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
            // 默认维度 1536，对应 text-embedding-3-small；必须与模型实际输出维度一致。
            embeddingDimension = 1536;
        }
        if (indexStaleAfter == null || indexStaleAfter.isZero() || indexStaleAfter.isNegative()) {
            indexStaleAfter = Duration.ofMinutes(15);
        }
    }
}
