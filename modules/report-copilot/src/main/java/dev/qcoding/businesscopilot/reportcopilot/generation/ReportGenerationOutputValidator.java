package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 校验模型输出中的每条事实都绑定到当前请求的证据。 */
public class ReportGenerationOutputValidator {

    public ValidationResult validate(LlmReportOutput output, List<ReportSource> sources) {
        if (output == null) {
            return new ValidationResult(false, List.of("AI 模型未返回结构化报告。"));
        }
        Set<String> validSourceIds = sources.stream().map(ReportSource::sourceId).collect(java.util.stream.Collectors.toSet());
        List<String> violations = new ArrayList<>();
        requireSourceIds("执行摘要", output.executiveSummary(), output.executiveSummarySourceIds(), validSourceIds, violations);
        output.metricHighlights().forEach(item -> validateMetricHighlight(item, sources, validSourceIds, violations));
        output.completedItems().forEach(item -> requireSourceIds("已完成事项", item.text(), item.sourceIds(), validSourceIds, violations));
        output.risks().forEach(item -> requireSourceIds("风险项", item.text(), item.sourceIds(), validSourceIds, violations));
        output.actionItems().forEach(item -> {
            if (item.origin() != ReportActionItemOrigin.SOURCE_ACTION) {
                violations.add("来源行动项必须标记为 SOURCE_ACTION。");
            }
            requireSourceIds("行动项", item.text(), item.sourceIds(), validSourceIds, violations);
        });
        output.suggestions().forEach(item -> {
            if (item.origin() != ReportActionItemOrigin.AI_SUGGESTION) {
                violations.add("AI 建议必须标记为 AI_SUGGESTION。");
            }
            if (!item.sourceIds().isEmpty()) {
                violations.add("AI 建议不得伪装成有来源依据的事实。");
            }
        });
        output.citations().forEach(citation -> {
            if (citation == null || citation.sourceId() == null || !validSourceIds.contains(citation.sourceId())) {
                violations.add("引用指向当前请求之外的来源。");
            }
        });
        return new ValidationResult(violations.isEmpty(), List.copyOf(violations));
    }

    private void requireSourceIds(String label, String text, List<String> sourceIds, Set<String> validSourceIds,
                                  List<String> violations) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sourceIds == null || sourceIds.isEmpty()) {
            violations.add(label + "缺少来源 ID。");
            return;
        }
        if (sourceIds.stream().anyMatch(sourceId -> !validSourceIds.contains(sourceId))) {
            violations.add(label + "引用了当前请求之外的来源。");
        }
    }

    private void validateMetricHighlight(MetricHighlight highlight, List<ReportSource> sources,
                                         Set<String> validSourceIds, List<String> violations) {
        requireSourceIds("指标亮点", highlight.summary(), highlight.sourceIds(), validSourceIds, violations);
        boolean matchesSourceMetric = highlight.sourceIds().stream()
                .flatMap(sourceId -> sources.stream().filter(source -> source.sourceId().equals(sourceId)))
                .anyMatch(source -> source.sourceType() == dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType.METRIC
                        && highlight.metricName().equals(source.attributes().get("name"))
                        && highlight.metricValue().equals(source.attributes().get("value"))
                        && highlight.unit().equals(source.attributes().get("unit")));
        if (!matchesSourceMetric) {
            violations.add("指标亮点与所引用的指标来源不完全一致。");
        }
    }

    public record ValidationResult(boolean valid, List<String> violations) {
    }
}
