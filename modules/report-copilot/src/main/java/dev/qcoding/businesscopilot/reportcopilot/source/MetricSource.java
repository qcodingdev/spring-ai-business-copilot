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
        Instant collectedAt,
        String providerId,
        String sourceVersion,
        String sourceTimezone,
        Instant validUntil) {

    public MetricSource(String name, BigDecimal value, String unit, LocalDate periodStart,
                        LocalDate periodEnd, Instant collectedAt) {
        this(name, value, unit, periodStart, periodEnd, collectedAt,
                "client-metric", "1", "UTC", null);
    }
}
