package dev.qcoding.businesscopilot.supportcopilot.audit;

import java.time.Instant;

/**
 * Immutable audit log entry for Support Copilot operations.
 *
 * <p>客服审计日志。记录工单分析、草稿生成、人工确认/取消和异常等全生命周期事件。
 * 不记录未脱敏客户原文，不记录未脱敏草稿内容。</p>
 *
 * @param id             primary key
 * @param requestId      request identifier for cross-service tracing
 * @param ticketId       associated ticket ID
 * @param eventType      CLASSIFIED, DRAFTED, NEEDS_HUMAN, CONFIRMED, CANCELED, FAILED
 * @param category       ticket classification category at event time
 * @param urgency        urgency level at event time
 * @param riskLevel      risk level at event time
 * @param citedChunkIds  comma-separated chunk IDs cited in the draft
 * @param modelName      AI model used for this operation
 * @param latencyMs      processing latency in milliseconds
 * @param errorMessage   error details if event is FAILED
 * @param createdAt      timestamp of log creation
 */
public record SupportAuditLog(
        Long id,
        String requestId,
        Long ticketId,
        String eventType,
        String category,
        String urgency,
        String riskLevel,
        String citedChunkIds,
        String modelName,
        Long latencyMs,
        String errorMessage,
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

    public SupportAuditLog(Long id, String requestId, Long ticketId, String eventType,
                           String category, String urgency, String riskLevel,
                           String citedChunkIds, String modelName, Long latencyMs,
                           String errorMessage, Instant createdAt) {
        this(id, requestId, ticketId, eventType, category, urgency, riskLevel,
                citedChunkIds, modelName, latencyMs, errorMessage,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, createdAt);
    }
}
