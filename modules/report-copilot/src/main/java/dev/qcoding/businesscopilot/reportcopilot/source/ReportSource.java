package dev.qcoding.businesscopilot.reportcopilot.source;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sanitized evidence made available to the report-generation flow.
 *
 * <p>IDs are generated server-side for each preview, and the hash is calculated from sanitized,
 * normalized content so later report guardrails can detect a changed source.</p>
 */
public record ReportSource(
        String sourceId,
        UUID snapshotId,
        ReportSourceType sourceType,
        String title,
        String sanitizedContent,
        String sourceHash,
        Map<String, String> attributes,
        String providerId,
        String sourceVersion,
        Instant observedAt,
        String sourceTimezone,
        String sourceUnit,
        Instant validUntil,
        SourceFreshness freshness) {

    public ReportSource(String sourceId, ReportSourceType sourceType, String title,
                        String sanitizedContent, String sourceHash, Map<String, String> attributes) {
        this(sourceId, UUID.randomUUID(), sourceType, title, sanitizedContent, sourceHash,
                attributes, "legacy", "1", Instant.EPOCH, "UTC", "", null,
                SourceFreshness.UNKNOWN);
    }

    public ReportSource {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        snapshotId = snapshotId == null ? UUID.randomUUID() : snapshotId;
        freshness = freshness == null ? SourceFreshness.UNKNOWN : freshness;
    }
}
