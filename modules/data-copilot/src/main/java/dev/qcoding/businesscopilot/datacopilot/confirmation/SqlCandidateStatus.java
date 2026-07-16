package dev.qcoding.businesscopilot.datacopilot.confirmation;

/** Database-backed SQL candidate lifecycle. */
public enum SqlCandidateStatus {
    PENDING,
    CONSUMED,
    EXPIRED,
    REJECTED
}
