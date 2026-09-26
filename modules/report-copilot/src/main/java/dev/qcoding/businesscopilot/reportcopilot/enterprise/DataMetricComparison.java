package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.reportcopilot.generation.ReportComparisonCalculator;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.source.RawReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compares only explicit, compatible business period totals; never compares result row counts. */
public class DataMetricComparison {
    public RawReportSource compare(Map<String, String> current, Map<String, String> previous,
                                   String currentRef, String previousRef, ReportPeriod period) {
        String reason = incompatibility(current, previous, period);
        var attributes = new LinkedHashMap<String, String>();
        attributes.put("currentSource", currentRef);
        attributes.put("previousSource", previousRef);
        attributes.put("scopeEvidence", "HUMAN_CONFIRMED_SQL_AND_PERIOD");
        if (reason == null) {
            var calculation = new ReportComparisonCalculator().compute(new BigDecimal(current.get("value")),
                    new BigDecimal(previous.get("value")), current.get("unit"),
                    current.get("periodStart") + "/" + current.get("periodEnd"),
                    previous.get("periodStart") + "/" + previous.get("periodEnd"));
            attributes.putAll(current);
            attributes.put("currentValue", current.get("value"));
            attributes.put("previousValue", previous.get("value"));
            attributes.put("previousPeriodStart", previous.get("periodStart"));
            attributes.put("previousPeriodEnd", previous.get("periodEnd"));
            attributes.put("sourceUnit", current.get("unit"));
            attributes.put("formula", calculation.formula());
            attributes.put("name", current.get("metricKey") + ".changePercent");
            if (calculation.computable()) {
                attributes.put("value", calculation.changePercent().toPlainString());
                attributes.put("absoluteDelta", calculation.absoluteDelta().toPlainString());
                attributes.put("needsReview", String.valueOf(calculation.needsReview()));
            } else reason = calculation.notComputableReason();
        }
        attributes.put("unit", "percent");
        attributes.put("comparisonStatus", reason == null ? "COMPARABLE" : "NOT_COMPARABLE");
        if (reason != null) {
            attributes.put("reason", reason);
            attributes.remove("value");
        }
        Instant now = Instant.now();
        Instant validUntil = now.plusSeconds(86400);
        for (var metric : java.util.List.of(current, previous)) {
            if (metric.containsKey("validUntil")) {
                Instant sourceExpiry = Instant.parse(metric.get("validUntil"));
                if (sourceExpiry.isBefore(validUntil)) validUntil = sourceExpiry;
            }
        }
        return new RawReportSource(reason == null ? ReportSourceType.METRIC : ReportSourceType.KNOWLEDGE,
                "业务指标环比", reason == null ? "已核验期间总量的确定性比较：" + attributes
                        : "不可比较：" + reason + "。不得推断增长率。来源：" + currentRef + "、" + previousRef,
                attributes, "report-difference", currentRef + "/" + previousRef, now,
                period.timezone(), "percent", validUntil);
    }

    private String incompatibility(Map<String, String> current, Map<String, String> previous, ReportPeriod period) {
        if (!"period-total-v1".equals(current.get("schema")) || !"period-total-v1".equals(previous.get("schema")))
            return "缺少已确认的指标及业务周期";
        if ("true".equals(current.get("truncated")) || "true".equals(previous.get("truncated"))) return "查询来源已截断";
        for (String field : new String[]{"metricKey", "metricVersion", "unit", "grain", "timezone"}) {
            if (current.get(field) == null || current.get(field).isBlank() || !current.get(field).equals(previous.get(field)))
                return "指标版本、单位、粒度或时区不一致";
        }
        if (!"PERIOD_TOTAL".equals(current.get("grain"))) return "不是期间总量";
        try {
            LocalDate cs = LocalDate.parse(current.get("periodStart")), ce = LocalDate.parse(current.get("periodEnd"));
            LocalDate ps = LocalDate.parse(previous.get("periodStart")), pe = LocalDate.parse(previous.get("periodEnd"));
            if (ce.isBefore(cs) || pe.isBefore(ps) || !pe.plusDays(1).equals(cs)
                    || ChronoUnit.DAYS.between(cs, ce) != ChronoUnit.DAYS.between(ps, pe))
                return "需要同长度且相邻的业务周期";
            if (!cs.equals(period.periodStart()) || !ce.equals(period.periodEnd()) || !period.timezone().equals(current.get("timezone")))
                return "当前指标周期与报告周期不一致";
            new BigDecimal(current.get("value"));
            new BigDecimal(previous.get("value"));
        } catch (RuntimeException ex) { return "来源周期或数值无效"; }
        return null;
    }
}
