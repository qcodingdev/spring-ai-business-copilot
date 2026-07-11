package dev.qcoding.businesscopilot.reportcopilot;

import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * Configuration that bounds Report Copilot input and future draft lifecycle behavior.
 */
@ConfigurationProperties(prefix = "business-copilot.report-copilot")
public record ReportCopilotProperties(
        boolean enabled,
        int maxPeriodDays,
        int maxSourceCount,
        int maxSourceLength,
        int maxMetricSources,
        int maxTaskSources,
        int maxMeetingNoteSources,
        Duration draftTtl,
        Set<ReportType> allowedReportTypes,
        boolean markdownExportEnabled) {

    public ReportCopilotProperties {
        if (maxPeriodDays <= 0) {
            maxPeriodDays = 31;
        }
        if (maxSourceCount <= 0) {
            maxSourceCount = 50;
        }
        if (maxSourceLength <= 0) {
            maxSourceLength = 4000;
        }
        if (maxMetricSources <= 0) {
            maxMetricSources = 20;
        }
        if (maxTaskSources <= 0) {
            maxTaskSources = 20;
        }
        if (maxMeetingNoteSources <= 0) {
            maxMeetingNoteSources = 10;
        }
        if (draftTtl == null || draftTtl.isNegative() || draftTtl.isZero()) {
            draftTtl = Duration.ofMinutes(30);
        }
        if (allowedReportTypes == null || allowedReportTypes.isEmpty()) {
            allowedReportTypes = Set.of(ReportType.TEAM_WEEKLY, ReportType.BUSINESS_WEEKLY,
                    ReportType.PROJECT_STATUS);
        } else {
            allowedReportTypes = Set.copyOf(allowedReportTypes);
        }
    }
}
