package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;

import java.time.Instant;

/** Persisted report draft metadata. The structured content has already passed output validation. */
public record ReportDraft(Long id, Long requestId, LlmReportOutput content, ReportDraftStatus status,
                          String reviewReasons, String confirmationToken, Instant expiresAt,
                          Instant createdAt, Instant updatedAt) {
}
