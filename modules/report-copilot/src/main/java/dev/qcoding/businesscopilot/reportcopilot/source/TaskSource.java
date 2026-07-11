package dev.qcoding.businesscopilot.reportcopilot.source;

/** Structured task evidence. Assignee values are accepted only as aliases and are masked on output. */
public record TaskSource(
        String title,
        String status,
        String assigneeAlias,
        String sourceDescription) {
}
