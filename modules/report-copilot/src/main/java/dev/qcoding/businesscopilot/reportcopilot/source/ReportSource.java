package dev.qcoding.businesscopilot.reportcopilot.source;

import java.util.Map;

/**
 * Sanitized evidence made available to the report-generation flow.
 *
 * <p>IDs are generated server-side for each preview, and the hash is calculated from sanitized,
 * normalized content so later report guardrails can detect a changed source.</p>
 */
public record ReportSource(
        String sourceId,
        ReportSourceType sourceType,
        String title,
        String sanitizedContent,
        String sourceHash,
        Map<String, String> attributes) {

    public ReportSource {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
