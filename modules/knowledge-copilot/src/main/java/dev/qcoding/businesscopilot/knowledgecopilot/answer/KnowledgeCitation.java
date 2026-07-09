package dev.qcoding.businesscopilot.knowledgecopilot.answer;

/**
 * A single citation referencing a chunk from the retrieval results.
 *
 * <p>引用元数据：从模型输出的 JSON citations 中提取，记录引用的 chunk ID
 * 和模型给出的引用理由。每个 ANSWERED 状态至少需要一个 citation。</p>
 *
 * @param chunkId the chunk ID the answer references — must be present in the retrieval results
 * @param excerpt short supporting text excerpt or rationale
 */
public record KnowledgeCitation(
        Long chunkId,
        String excerpt) {
}
