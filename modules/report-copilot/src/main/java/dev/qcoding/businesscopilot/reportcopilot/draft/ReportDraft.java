package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;

import java.time.Instant;

/** Persisted report draft metadata. NEEDS_REVIEW drafts deliberately carry no untrusted model content. */
public record ReportDraft(Long id, Long requestId, LlmReportOutput content, ReportDraftStatus status,
                          String reviewReasons, String confirmationToken, Instant expiresAt,
                          Instant createdAt, Instant updatedAt) {
}
