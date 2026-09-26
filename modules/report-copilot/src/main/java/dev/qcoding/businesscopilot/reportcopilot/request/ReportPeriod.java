package dev.qcoding.businesscopilot.reportcopilot.request;

import java.time.LocalDate;
import java.time.ZoneId;

/** Inclusive reporting period supplied for a report request. */
public record ReportPeriod(LocalDate periodStart, LocalDate periodEnd, String timezone) {
    public ReportPeriod(LocalDate periodStart, LocalDate periodEnd) {
        this(periodStart, periodEnd, "Asia/Shanghai");
    }

    public ReportPeriod {
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
            throw new dev.qcoding.businesscopilot.commonweb.api.BusinessException(
                    dev.qcoding.businesscopilot.commonweb.api.ErrorCode.VALIDATION_ERROR, "报告起止日期无效");
        }
        timezone = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone;
        try {
            ZoneId.of(timezone);
        } catch (java.time.DateTimeException ex) {
            throw new dev.qcoding.businesscopilot.commonweb.api.BusinessException(
                    dev.qcoding.businesscopilot.commonweb.api.ErrorCode.VALIDATION_ERROR, "报告时区无效");
        }
    }
}
