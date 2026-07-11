package dev.qcoding.businesscopilot.reportcopilot.draft;

/** Lifecycle state for a persisted report draft. */
public enum ReportDraftStatus {
    DRAFTED,
    NEEDS_REVIEW,
    CONFIRMED,
    CANCELED,
    FAILED
}
