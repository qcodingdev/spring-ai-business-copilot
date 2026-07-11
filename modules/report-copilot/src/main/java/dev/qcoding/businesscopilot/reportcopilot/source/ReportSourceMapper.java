package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps validated request models to a common raw-source representation. */
public class ReportSourceMapper {

    public List<RawReportSource> map(ReportGenerateRequest request) {
        List<RawReportSource> sources = new ArrayList<>();
        if (request.metrics() != null) {
            request.metrics().forEach(metric -> sources.add(new RawReportSource(ReportSourceType.METRIC,
                    "Metric: " + metric.name(),
                    "Value: " + metric.value().toPlainString() + " " + metric.unit()
                            + "\nPeriod: " + metric.periodStart() + " to " + metric.periodEnd()
                            + "\nCollected at: " + metric.collectedAt(),
                    Map.of("name", metric.name(), "value", metric.value().toPlainString(), "unit", metric.unit(),
                            "periodStart", metric.periodStart().toString(), "periodEnd", metric.periodEnd().toString(),
                            "collectedAt", metric.collectedAt().toString()))));
        }
        if (request.tasks() != null) {
            request.tasks().forEach(task -> sources.add(new RawReportSource(ReportSourceType.TASK, task.title(),
                    "Status: " + task.status() + "\nAssignee alias: " + nullToEmpty(task.assigneeAlias())
                            + "\nSource: " + task.sourceDescription(),
                    Map.of("status", task.status(), "assigneeAlias", nullToEmpty(task.assigneeAlias()),
                            "source", task.sourceDescription()))));
        }
        if (request.meetingNotes() != null) {
            request.meetingNotes().forEach(note -> sources.add(new RawReportSource(ReportSourceType.MEETING_NOTE,
                    note.title(), note.content(), Map.of("recordedAt", note.recordedAt().toString()))));
        }
        return List.copyOf(sources);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
