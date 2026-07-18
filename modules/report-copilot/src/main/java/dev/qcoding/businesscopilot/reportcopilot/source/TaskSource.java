package dev.qcoding.businesscopilot.reportcopilot.source;

import java.time.Instant;

/** Structured task evidence. Assignee values are accepted only as aliases and are masked on output. */
public record TaskSource(
        String title,
        String status,
        String assigneeAlias,
        String sourceDescription,
        String providerId,
        String sourceVersion,
        Instant observedAt,
        String sourceTimezone,
        Instant validUntil) {

    public TaskSource(String title, String status, String assigneeAlias, String sourceDescription) {
        this(title, status, assigneeAlias, sourceDescription,
                "client-task", "1", Instant.EPOCH, "UTC", null);
    }
}
