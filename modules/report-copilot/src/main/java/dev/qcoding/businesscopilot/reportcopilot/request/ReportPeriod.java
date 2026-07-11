package dev.qcoding.businesscopilot.reportcopilot.request;

import java.time.LocalDate;

/** Inclusive reporting period supplied for a report request. */
public record ReportPeriod(LocalDate periodStart, LocalDate periodEnd) {
}
