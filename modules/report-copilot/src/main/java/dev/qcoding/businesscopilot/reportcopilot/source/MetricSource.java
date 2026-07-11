package dev.qcoding.businesscopilot.reportcopilot.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Structured metric evidence. Values are kept as data, never recalculated by this module. */
public record MetricSource(
        String name,
        BigDecimal value,
        String unit,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant collectedAt) {
}
