package dev.qcoding.businesscopilot.knowledgecopilot.answer;

/**
 * A single citation referencing a chunk from the retrieval results.
 *
 * <p>引用元数据：模型只负责选择本次召回中的 chunk ID，摘录由服务端从已召回
 * 分片生成，不能使用模型自行改写的“原文”。每个 ANSWERED 状态至少需要一个 citation。</p>
 *
 * @param chunkId the chunk ID the answer references — must be present in the retrieval results
 * @param excerpt 服务端从已召回分片生成的可信摘录
 */
public record KnowledgeCitation(
        Long chunkId,
        String excerpt) {
}
