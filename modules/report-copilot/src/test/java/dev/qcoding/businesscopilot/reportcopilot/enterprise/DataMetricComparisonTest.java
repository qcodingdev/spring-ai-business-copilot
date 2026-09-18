package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DataMetricComparisonTest {
    private final ReportPeriod period = new ReportPeriod(LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-14"));
    private Map<String, String> metric(String value, String start, String end) {
        return new HashMap<>(Map.of("schema", "period-total-v1", "metricKey", "revenue", "metricVersion", "2",
                "unit", "CNY", "grain", "PERIOD_TOTAL", "timezone", "Asia/Shanghai", "value", value,
                "periodStart", start, "periodEnd", end));
    }
    private Map<String, String> current() { return metric("120", "2026-09-08", "2026-09-14"); }
    private Map<String, String> previous() { return metric("100", "2026-09-01", "2026-09-07"); }

    @Test void comparesRevenueRatherThanTwoSingleRowCounts() {
        var source = new DataMetricComparison().compare(current(), previous(), "current", "previous", period);
        assertThat(source.attributes()).containsEntry("value", "20.00").containsEntry("absoluteDelta", "20")
                .containsEntry("currentSource", "current").containsEntry("previousSource", "previous")
                .containsEntry("sourceUnit", "CNY").containsEntry("needsReview", "true");
    }

    @Test void refusesMissingScopesTruncationVersionUnitTimezoneAndPeriodMismatch() {
        for (String field : new String[]{"schema", "metricKey", "metricVersion", "unit", "grain", "timezone", "periodStart", "periodEnd", "value"}) {
            var altered = previous();
            altered.put(field, "invalid");
            var source = new DataMetricComparison().compare(current(), altered, "c", "p", period);
            assertThat(source.attributes()).as(field).containsEntry("comparisonStatus", "NOT_COMPARABLE").doesNotContainKey("value");
        }
        var truncated = current(); truncated.put("truncated", "true");
        assertThat(new DataMetricComparison().compare(truncated, previous(), "c", "p", period).attributes())
                .containsEntry("comparisonStatus", "NOT_COMPARABLE");
        assertThat(new DataMetricComparison().compare(current(), previous(), "c", "p",
                new ReportPeriod(period.periodStart().plusDays(1), period.periodEnd())).attributes())
                .containsEntry("comparisonStatus", "NOT_COMPARABLE");
    }

    @Test void comparisonCannotOutliveEitherSource() {
        var current = current(); current.put("validUntil", "2030-01-01T00:00:00Z");
        var previous = previous(); previous.put("validUntil", "2020-01-01T00:00:00Z");
        assertThat(new DataMetricComparison().compare(current, previous, "c", "p", period).validUntil())
                .isEqualTo(java.time.Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test void zeroDenominatorHasReasonAndNoNumericGrowth() {
        var zero = previous(); zero.put("value", "0");
        var source = new DataMetricComparison().compare(current(), zero, "c", "p", period);
        assertThat(source.attributes()).containsEntry("comparisonStatus", "NOT_COMPARABLE").doesNotContainKey("value");
        assertThat(source.content()).contains("为 0");
    }
}
