package dev.qcoding.businesscopilot.reportcopilot.source;

import java.time.Instant;

/** Free-form meeting or knowledge evidence. The text is data, not executable instructions. */
public record TextSource(String title, String content, Instant recordedAt) {
}
