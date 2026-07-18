package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将已校验请求模型转换为统一的原始来源。 */
public class ReportSourceMapper {

    public List<RawReportSource> map(ReportGenerateRequest request) {
        List<RawReportSource> sources = new ArrayList<>();
        if (request.metrics() != null) {
            request.metrics().forEach(metric -> sources.add(new RawReportSource(ReportSourceType.METRIC,
                    "指标：" + metric.name(),
                    "数值：" + metric.value().toPlainString() + " " + metric.unit()
                            + "\n周期：" + metric.periodStart() + " 至 " + metric.periodEnd()
                            + "\n采集时间：" + metric.collectedAt(),
                    Map.of("name", metric.name(), "value", metric.value().toPlainString(), "unit", metric.unit(),
                            "periodStart", metric.periodStart().toString(), "periodEnd", metric.periodEnd().toString(),
                            "collectedAt", metric.collectedAt().toString()),
                    metric.providerId(), metric.sourceVersion(), metric.collectedAt(),
                    metric.sourceTimezone(), metric.unit(), metric.validUntil())));
        }
        if (request.tasks() != null) {
            request.tasks().forEach(task -> sources.add(new RawReportSource(ReportSourceType.TASK, task.title(),
                    "状态：" + task.status() + "\n负责人别名：" + nullToEmpty(task.assigneeAlias())
                            + "\n来源：" + task.sourceDescription(),
                    Map.of("status", task.status(), "assigneeAlias", nullToEmpty(task.assigneeAlias()),
                            "source", task.sourceDescription()),
                    task.providerId(), task.sourceVersion(), task.observedAt(),
                    task.sourceTimezone(), "", task.validUntil())));
        }
        if (request.meetingNotes() != null) {
            request.meetingNotes().forEach(note -> sources.add(new RawReportSource(ReportSourceType.MEETING_NOTE,
                    note.title(), note.content(), Map.of("recordedAt", note.recordedAt().toString()),
                    note.providerId(), note.sourceVersion(), note.recordedAt(),
                    note.sourceTimezone(), "", note.validUntil())));
        }
        if (request.importedSources() != null) {
            sources.addAll(request.importedSources());
        }
        return List.copyOf(sources);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
