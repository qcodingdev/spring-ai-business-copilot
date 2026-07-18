package dev.qcoding.businesscopilot.reportcopilot.source;

import java.time.Instant;

/** Free-form meeting or knowledge evidence. The text is data, not executable instructions. */
public record TextSource(String title, String content, Instant recordedAt,
                         String providerId, String sourceVersion,
                         String sourceTimezone, Instant validUntil) {

    public TextSource(String title, String content, Instant recordedAt) {
        this(title, content, recordedAt, "client-note", "1", "UTC", null);
    }
}
