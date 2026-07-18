package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

/** 单次异步知识索引请求的持久化生命周期。 */
public enum KnowledgeIndexJobStatus {
    PENDING,
    PROCESSING,
    RETRYABLE,
    COMPLETED,
    FAILED,
    CANCELED
}
