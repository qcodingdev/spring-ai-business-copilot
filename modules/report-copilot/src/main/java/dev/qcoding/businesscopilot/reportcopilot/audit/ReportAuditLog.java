package dev.qcoding.businesscopilot.reportcopilot.audit;

/** Minimal audit metadata; never stores report body or unsanitized source content. */
public record ReportAuditLog(Long requestId, Long draftId, String eventType, int sourceCount,
                             String citedSourceIds, String modelName, String status, String errorMessage) {
}
