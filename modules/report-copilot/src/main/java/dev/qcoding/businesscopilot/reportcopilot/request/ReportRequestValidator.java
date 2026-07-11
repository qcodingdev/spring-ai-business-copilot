package dev.qcoding.businesscopilot.reportcopilot.request;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.source.MetricSource;
import dev.qcoding.businesscopilot.reportcopilot.source.TaskSource;
import dev.qcoding.businesscopilot.reportcopilot.source.TextSource;

import java.time.temporal.ChronoUnit;
import java.util.List;

/** Deterministic validation for report inputs before they reach the source normalizer or a model. */
public class ReportRequestValidator {

    private final ReportCopilotProperties properties;

    public ReportRequestValidator(ReportCopilotProperties properties) {
        this.properties = properties;
    }

    public void validate(ReportGenerateRequest request) {
        if (request == null || request.reportType() == null
                || !properties.allowedReportTypes().contains(request.reportType())) {
            reject("The report type is not allowed.");
        }
        validatePeriod(request.period());
        if (isBlank(request.title()) || request.title().trim().length() > 300) {
            reject("The report title is required and must not exceed 300 characters.");
        }

        List<MetricSource> metrics = nullSafe(request.metrics());
        List<TaskSource> tasks = nullSafe(request.tasks());
        List<TextSource> meetingNotes = nullSafe(request.meetingNotes());
        if (metrics.size() > properties.maxMetricSources() || tasks.size() > properties.maxTaskSources()
                || meetingNotes.size() > properties.maxMeetingNoteSources()
                || metrics.size() + tasks.size() + meetingNotes.size() > properties.maxSourceCount()) {
            reject("The report exceeds the configured source limits.");
        }
        metrics.forEach(this::validateMetric);
        tasks.forEach(this::validateTask);
        meetingNotes.forEach(this::validateMeetingNote);
    }

    private void validatePeriod(ReportPeriod period) {
        if (period == null || period.periodStart() == null || period.periodEnd() == null
                || period.periodStart().isAfter(period.periodEnd())) {
            reject("The report period is invalid.");
        }
        long days = ChronoUnit.DAYS.between(period.periodStart(), period.periodEnd()) + 1;
        if (days > properties.maxPeriodDays()) {
            reject("The report period exceeds the configured maximum.");
        }
    }

    private void validateMetric(MetricSource source) {
        if (source == null || isBlank(source.name()) || source.value() == null || isBlank(source.unit())
                || source.periodStart() == null || source.periodEnd() == null || source.collectedAt() == null
                || source.periodStart().isAfter(source.periodEnd())) {
            reject("A metric source must include name, value, unit, period, and collection time.");
        }
        validateLength(source.name(), "A metric name exceeds the configured length limit.");
        validateLength(source.unit(), "A metric unit exceeds the configured length limit.");
    }

    private void validateTask(TaskSource source) {
        if (source == null || isBlank(source.title()) || isBlank(source.status())
                || isBlank(source.sourceDescription())) {
            reject("A task source must include title, status, and source description.");
        }
        validateLength(source.title(), "A task title exceeds the configured length limit.");
        validateLength(source.status(), "A task status exceeds the configured length limit.");
        validateLength(source.assigneeAlias(), "A task assignee alias exceeds the configured length limit.");
        validateLength(source.sourceDescription(), "A task source description exceeds the configured length limit.");
    }

    private void validateMeetingNote(TextSource source) {
        if (source == null || isBlank(source.title()) || isBlank(source.content()) || source.recordedAt() == null) {
            reject("A meeting note must include title, content, and recorded time.");
        }
        validateLength(source.title(), "A meeting note title exceeds the configured length limit.");
        validateLength(source.content(), "A meeting note exceeds the configured length limit.");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void validateLength(String value, String message) {
        if (value != null && value.trim().length() > properties.maxSourceLength()) {
            reject(message);
        }
    }

    private void reject(String message) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
