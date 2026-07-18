package dev.qcoding.businesscopilot.reportcopilot.request;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.source.MetricSource;
import dev.qcoding.businesscopilot.reportcopilot.source.RawReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.TaskSource;
import dev.qcoding.businesscopilot.reportcopilot.source.TextSource;

import java.time.temporal.ChronoUnit;
import java.util.List;

/** 在报告输入进入来源标准化和模型调用前执行确定性校验。 */
public class ReportRequestValidator {

    private final ReportCopilotProperties properties;

    public ReportRequestValidator(ReportCopilotProperties properties) {
        this.properties = properties;
    }

    public void validate(ReportGenerateRequest request) {
        if (request == null || request.reportType() == null
                || !properties.allowedReportTypes().contains(request.reportType())) {
            reject("不支持该报告类型。");
        }
        validatePeriod(request.period());
        if (isBlank(request.title()) || request.title().trim().length() > 300) {
            reject("报告标题不能为空且不能超过 300 个字符。");
        }

        List<MetricSource> metrics = nullSafe(request.metrics());
        List<TaskSource> tasks = nullSafe(request.tasks());
        List<TextSource> meetingNotes = nullSafe(request.meetingNotes());
        List<RawReportSource> importedSources = nullSafe(request.importedSources());
        if (metrics.size() > properties.maxMetricSources() || tasks.size() > properties.maxTaskSources()
                || meetingNotes.size() > properties.maxMeetingNoteSources()
                || metrics.size() + tasks.size() + meetingNotes.size() + importedSources.size()
                > properties.maxSourceCount()) {
            reject("报告来源数量超过配置限制。");
        }
        validateTemplate(request.templateId(), request.templateVersion());
        metrics.forEach(this::validateMetric);
        tasks.forEach(this::validateTask);
        meetingNotes.forEach(this::validateMeetingNote);
        importedSources.forEach(this::validateImportedSource);
    }

    private void validatePeriod(ReportPeriod period) {
        if (period == null || period.periodStart() == null || period.periodEnd() == null
                || period.periodStart().isAfter(period.periodEnd())) {
            reject("报告周期无效。");
        }
        long days = ChronoUnit.DAYS.between(period.periodStart(), period.periodEnd()) + 1;
        if (days > properties.maxPeriodDays()) {
            reject("报告周期超过配置的最大天数。");
        }
    }

    private void validateMetric(MetricSource source) {
        if (source == null || isBlank(source.name()) || source.value() == null || isBlank(source.unit())
                || source.periodStart() == null || source.periodEnd() == null || source.collectedAt() == null
                || source.periodStart().isAfter(source.periodEnd())) {
            reject("指标来源必须包含名称、数值、单位、周期和采集时间。");
        }
        validateLength(source.name(), "指标名称超过配置长度限制。");
        validateLength(source.unit(), "指标单位超过配置长度限制。");
    }

    private void validateTask(TaskSource source) {
        if (source == null || isBlank(source.title()) || isBlank(source.status())
                || isBlank(source.sourceDescription())) {
            reject("任务来源必须包含标题、状态和来源说明。");
        }
        validateLength(source.title(), "任务标题超过配置长度限制。");
        validateLength(source.status(), "任务状态超过配置长度限制。");
        validateLength(source.assigneeAlias(), "任务负责人别名超过配置长度限制。");
        validateLength(source.sourceDescription(), "任务来源说明超过配置长度限制。");
    }

    private void validateMeetingNote(TextSource source) {
        if (source == null || isBlank(source.title()) || isBlank(source.content()) || source.recordedAt() == null) {
            reject("会议纪要必须包含标题、内容和记录时间。");
        }
        validateLength(source.title(), "会议纪要标题超过配置长度限制。");
        validateLength(source.content(), "会议纪要内容超过配置长度限制。");
    }

    private void validateImportedSource(RawReportSource source) {
        if (source == null || source.sourceType() == null || isBlank(source.title()) || isBlank(source.content())) {
            reject("导入来源必须包含 sourceType、title 和 content。");
        }
        validateLength(source.title(), "导入来源标题超过配置长度限制。");
        validateLength(source.content(), "导入来源内容超过配置长度限制。");
    }

    private void validateTemplate(String templateId, String templateVersion) {
        if (templateId != null && (templateId.isBlank() || templateId.trim().length() > 100)) {
            reject("报告 templateId 无效。");
        }
        if (templateVersion != null && (templateVersion.isBlank() || templateVersion.trim().length() > 50)) {
            reject("报告 templateVersion 无效。");
        }
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
