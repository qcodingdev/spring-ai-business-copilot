package dev.qcoding.businesscopilot.knowledgecopilot.audit;

import java.time.Instant;

/**
 * Immutable audit log entry for a single Knowledge Copilot Q&A session.
 *
 * <p>知识问答审计日志。记录从检索到答案生成的关键元信息。
 * 不记录完整原始文档内容和敏感字段明文值。</p>
 *
 * @param id                 primary key
 * @param requestId          request identifier for cross-service tracing
 * @param question           the user's natural language question
 * @param retrievedChunkIds  comma-separated chunk IDs returned by retrieval
 * @param citedChunkIds      comma-separated chunk IDs actually cited in the answer
 * @param answerStatus       ANSWERED, NO_EVIDENCE, REJECTED, or FAILED
 * @param refusalReason      reason for refusal/error, if any
 * @param modelName          chat model used for answer generation
 * @param embeddingModel     embedding model used for retrieval
 * @param latencyMs          total processing latency in milliseconds
 * @param createdAt          timestamp of log creation
 */
public record KnowledgeQaAuditLog(
        Long id,
        String requestId,
        String question,
        String retrievedChunkIds,
        String citedChunkIds,
        String answerStatus,
        String refusalReason,
        String modelName,
        String embeddingModel,
        Long latencyMs,
        String creatorActorId,
        String actionActorId,
        String providerName,
        String providerRequestId,
        String promptName,
        String promptVersion,
        String promptHash,
        String policyVersion,
        String violationCodes,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason,
        Instant anonymizedAt,
        Instant createdAt) {

    public KnowledgeQaAuditLog(Long id, String requestId, String question,
                               String retrievedChunkIds, String citedChunkIds,
                               String answerStatus, String refusalReason, String modelName,
                               String embeddingModel, Long latencyMs, Instant createdAt) {
        this(id, requestId, question, retrievedChunkIds, citedChunkIds,
                answerStatus, refusalReason, modelName, embeddingModel, latencyMs,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, createdAt);
    }
}
