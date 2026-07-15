package dev.qcoding.businesscopilot.audit;

/**
 * Immutable audit log entry for a single Data Copilot query.
 *
 * <p>查询审计日志记录。覆盖从问题、生成 SQL、校验、确认到执行的全链路。
 * 不记录完整敏感结果——只记录元信息。详见 {@link AuditService} 中相关注释。</p>
 *
 * @param id               primary key
 * @param requestId        request identifier for cross-service tracing
 * @param httpRequestId    HTTP request identifier for support correlation
 * @param actorId          authenticated actor that triggered the event
 * @param userQuestion     original natural-language question
 * @param generatedSql     SQL produced by the AI model
 * @param finalSql         SQL actually executed (may differ if guardrail stripped comments etc.)
 * @param validationStatus validation outcome string
 * @param validationErrors comma-separated validation violation descriptions
 * @param confirmed        whether the user confirmed execution
 * @param executionStatus  execution outcome string
 * @param rowCount         number of rows returned
 * @param errorMessage     error detail if any stage failed
 * @param modelName        AI model name used for generation
 * @param latencyMs        total latency from request to response (milliseconds)
 * @param createdAt        timestamp of log creation
 */
public record QueryAuditLog(
        Long id,
        String requestId,
        String httpRequestId,
        String actorId,
        String userQuestion,
        String generatedSql,
        String finalSql,
        String validationStatus,
        String validationErrors,
        boolean confirmed,
        String executionStatus,
        Integer rowCount,
        String errorMessage,
        String modelName,
        Long latencyMs,
        java.time.Instant createdAt) {
}
