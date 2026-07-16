package dev.qcoding.businesscopilot.reportcopilot.audit;

/** Minimal audit metadata; never stores report body or unsanitized source content. */
public record ReportAuditLog(Long requestId, Long draftId, String eventType, int sourceCount,
                             String citedSourceIds, String modelName, String status, String errorMessage,
                             Long latencyMs, String creatorActorId, String actionActorId,
                             String providerName, String providerRequestId, String promptName,
                             String promptVersion, String promptHash, String policyVersion,
                             String violationCodes, Integer inputTokens, Integer outputTokens,
                             String finishReason) {

    public ReportAuditLog(Long requestId, Long draftId, String eventType, int sourceCount,
                          String citedSourceIds, String modelName, String status, String errorMessage) {
        this(requestId, draftId, eventType, sourceCount, citedSourceIds, modelName,
                status, errorMessage, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
