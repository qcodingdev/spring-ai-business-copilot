package dev.qcoding.businesscopilot.reportcopilot.request;

import dev.qcoding.businesscopilot.reportcopilot.source.MetricSource;
import dev.qcoding.businesscopilot.reportcopilot.source.RawReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.TaskSource;
import dev.qcoding.businesscopilot.reportcopilot.source.TextSource;

import java.util.List;

/** Input for preparing the evidence pack that a later report-generation step will consume. */
public record ReportGenerateRequest(
        ReportType reportType,
        ReportPeriod period,
        String title,
        List<MetricSource> metrics,
        List<TaskSource> tasks,
        List<TextSource> meetingNotes,
        List<RawReportSource> importedSources,
        String templateId,
        String templateVersion) {

    public ReportGenerateRequest(ReportType reportType, ReportPeriod period, String title,
                                 List<MetricSource> metrics, List<TaskSource> tasks,
                                 List<TextSource> meetingNotes) {
        this(reportType, period, title, metrics, tasks, meetingNotes, List.of(), null, null);
    }
}
