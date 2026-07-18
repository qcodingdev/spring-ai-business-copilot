package dev.qcoding.businesscopilot.reportcopilot.source;

import java.time.Instant;
import java.util.Map;

/** 脱敏并分配请求范围证据 ID 之前的可信来源数据。 */
public record RawReportSource(
        ReportSourceType sourceType,
        String title,
        String content,
        Map<String, String> attributes,
        String providerId,
        String sourceVersion,
        Instant observedAt,
        String sourceTimezone,
        String sourceUnit,
        Instant validUntil) {

    public RawReportSource(ReportSourceType sourceType, String title, String content,
                           Map<String, String> attributes) {
        this(sourceType, title, content, attributes, null, null, null, null, null, null);
    }

    public RawReportSource {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
