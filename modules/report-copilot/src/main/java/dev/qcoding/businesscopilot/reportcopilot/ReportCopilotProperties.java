package dev.qcoding.businesscopilot.reportcopilot;

import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        boolean markdownExportEnabled,
        boolean htmlExportEnabled,
        int maxImportBytes,
        Duration sourceFreshnessTtl,
        String defaultTemplateId,
        String defaultTemplateVersion) {

    public ReportCopilotProperties(boolean enabled,
                                   int maxPeriodDays,
                                   int maxSourceCount,
                                   int maxSourceLength,
                                   int maxMetricSources,
                                   int maxTaskSources,
                                   int maxMeetingNoteSources,
                                   Duration draftTtl,
                                   Set<ReportType> allowedReportTypes,
                                   boolean markdownExportEnabled) {
        this(enabled, maxPeriodDays, maxSourceCount, maxSourceLength, maxMetricSources,
                maxTaskSources, maxMeetingNoteSources, draftTtl, allowedReportTypes,
                markdownExportEnabled, true, 1_048_576, Duration.ofDays(7),
                "evidence-weekly", "2.0");
    }

    @ConstructorBinding
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
                    ReportType.PROJECT_STATUS, ReportType.INCIDENT_REVIEW, ReportType.SALES_REVIEW);
        } else {
            allowedReportTypes = Set.copyOf(allowedReportTypes);
        }
        if (maxImportBytes <= 0) {
            maxImportBytes = 1_048_576;
        }
        if (sourceFreshnessTtl == null || sourceFreshnessTtl.isNegative() || sourceFreshnessTtl.isZero()) {
            sourceFreshnessTtl = Duration.ofDays(7);
        }
        if (defaultTemplateId == null || defaultTemplateId.isBlank()) {
            defaultTemplateId = "evidence-weekly";
        }
        if (defaultTemplateVersion == null || defaultTemplateVersion.isBlank()) {
            defaultTemplateVersion = "2.0";
        }
    }
}
