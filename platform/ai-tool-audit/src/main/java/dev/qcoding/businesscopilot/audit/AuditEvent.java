package dev.qcoding.businesscopilot.audit;

/**
 * A snapshot event submitted to {@link AuditService} at a given stage of the query lifecycle.
 *
 * @param requestId       request identifier for cross-service tracing
 * @param eventType       {@link AuditEventType} classification
 * @param userQuestion    original natural-language question
 * @param generatedSql    SQL produced by the AI model
 * @param finalSql        SQL actually executed (or null if not reached)
 * @param status          {@link AuditStatus} of the query at this point
 * @param validationErrors validation violation descriptions (comma-separated)
 * @param confirmed        whether the user confirmed execution
 * @param rowCount         number of rows returned (null if not executed)
 * @param errorMessage     error detail (null if no error)
 * @param modelName        AI model name
 * @param latencyMs        latency from request start to this event
 */
public record AuditEvent(
        String requestId,
        AuditEventType eventType,
        String userQuestion,
        String generatedSql,
        String finalSql,
        AuditStatus status,
        String validationErrors,
        boolean confirmed,
        Integer rowCount,
        String errorMessage,
        String modelName,
        Long latencyMs) {
}
