package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import java.util.List;
import java.util.Objects;

/**
 * 已完成模型计算、尚未写入数据库的知识向量索引。
 *
 * <p>模型调用与数据库提交被刻意分开：worker 可以在事务外完成耗时的向量生成，
 * 随后由索引任务生命周期服务在校验任务租约后原子替换向量并更新任务、文档状态。</p>
 */
public record PreparedKnowledgeIndex(
        EmbeddingIndexResult result,
        List<KnowledgeChunkEmbedding> embeddings) {

    public PreparedKnowledgeIndex {
        Objects.requireNonNull(result, "result must not be null");
        embeddings = List.copyOf(Objects.requireNonNull(embeddings, "embeddings must not be null"));
        if (result.chunkCount() != embeddings.size()) {
            throw new IllegalArgumentException("索引摘要的分片数量必须与待提交向量数量一致");
        }
    }
}
