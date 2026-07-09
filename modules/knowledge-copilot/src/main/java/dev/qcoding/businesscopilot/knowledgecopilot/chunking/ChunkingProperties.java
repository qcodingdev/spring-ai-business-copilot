package dev.qcoding.businesscopilot.knowledgecopilot.chunking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Knowledge Copilot document chunking strategy.
 *
 * <p>文档分片配置。控制目标分片长度和相邻分片重叠字符数。
 * 实际分片会优先沿标题和段落边界切分，再按 {@code chunkSize} 上限做二次裁剪，
 * 因此单个 chunk 的长度可能略小于配置值（边界对齐）。</p>
 *
 * @param chunkSize    target chunk content length in characters
 * @param chunkOverlap overlap between adjacent chunks in characters
 */
@ConfigurationProperties(prefix = "business-copilot.knowledge.chunking")
public record ChunkingProperties(
        int chunkSize,
        int chunkOverlap) {

    /** Defaults aligned with the V2 MVP boundary. */
    public ChunkingProperties {
        if (chunkSize <= 0) {
            // 默认目标分片长度 800 字符
            chunkSize = 800;
        }
        if (chunkOverlap < 0) {
            // 重叠不允许为负
            chunkOverlap = 0;
        }
        if (chunkOverlap >= chunkSize) {
            // 重叠必须小于分片长度，否则无法前进
            chunkOverlap = chunkSize / 4;
        }
    }
}
