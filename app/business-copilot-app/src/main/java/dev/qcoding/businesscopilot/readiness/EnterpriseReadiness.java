package dev.qcoding.businesscopilot.readiness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stable API and persistence models for the enterprise-readiness evidence loop. */
public final class EnterpriseReadiness {

    private EnterpriseReadiness() {
    }

    public enum Module { PLATFORM, DATA, KNOWLEDGE, SUPPORT, REPORT, HR }

    public enum CheckStatus { PASS, WARNING, BLOCKER }

    public enum OverallStatus { READY, ATTENTION, BLOCKED, NOT_CONFIGURED }

    public record Check(
            String checkId,
            Module module,
            CheckStatus status,
            long affectedCount,
            String threshold,
            String actionPath) {
    }

    public record Assessment(
            int schemaVersion,
            String applicationVersion,
            String runtimeMode,
            OverallStatus status,
            int passedCount,
            int warningCount,
            int blockerCount,
            List<Check> checks,
            String contentHash,
            Instant generatedAt,
            Instant validUntil) {
    }

    public record Snapshot(
            long id,
            UUID snapshotReference,
            int schemaVersion,
            String purpose,
            String applicationVersion,
            String runtimeMode,
            OverallStatus status,
            int passedCount,
            int warningCount,
            int blockerCount,
            List<Check> checks,
            String contentHash,
            String generatedBy,
            Instant generatedAt,
            Instant validUntil) {
    }

    public record SnapshotDraft(
            UUID snapshotReference,
            String purpose,
            Assessment assessment,
            String generatedBy) {
    }
}
