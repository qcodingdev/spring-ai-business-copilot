package dev.qcoding.businesscopilot.reportcopilot.source;

import java.util.Map;

/** Trusted source data before it is sanitized and assigned a request-scoped evidence ID. */
public record RawReportSource(
        ReportSourceType sourceType,
        String title,
        String content,
        Map<String, String> attributes) {

    public RawReportSource {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
