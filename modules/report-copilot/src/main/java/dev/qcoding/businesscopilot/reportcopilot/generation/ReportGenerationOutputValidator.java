package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validates that every factual model output is tied to evidence from the current request. */
public class ReportGenerationOutputValidator {

    public ValidationResult validate(LlmReportOutput output, List<ReportSource> sources) {
        if (output == null) {
            return new ValidationResult(false, List.of("AI model returned no structured report output."));
        }
        Set<String> validSourceIds = sources.stream().map(ReportSource::sourceId).collect(java.util.stream.Collectors.toSet());
        List<String> violations = new ArrayList<>();
        requireSourceIds("executive summary", output.executiveSummary(), output.executiveSummarySourceIds(), validSourceIds, violations);
        output.metricHighlights().forEach(item -> validateMetricHighlight(item, sources, validSourceIds, violations));
        output.completedItems().forEach(item -> requireSourceIds("completed item", item.text(), item.sourceIds(), validSourceIds, violations));
        output.risks().forEach(item -> requireSourceIds("risk", item.text(), item.sourceIds(), validSourceIds, violations));
        output.actionItems().forEach(item -> {
            if (item.origin() != ReportActionItemOrigin.SOURCE_ACTION) {
                violations.add("Action items must use SOURCE_ACTION origin.");
            }
            requireSourceIds("action item", item.text(), item.sourceIds(), validSourceIds, violations);
        });
        output.suggestions().forEach(item -> {
            if (item.origin() != ReportActionItemOrigin.AI_SUGGESTION) {
                violations.add("Suggestions must use AI_SUGGESTION origin.");
            }
            if (!item.sourceIds().isEmpty()) {
                violations.add("AI suggestions must not claim source evidence.");
            }
        });
        output.citations().forEach(citation -> {
            if (citation == null || citation.sourceId() == null || !validSourceIds.contains(citation.sourceId())) {
                violations.add("Citation refers to a source outside the current request.");
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
            violations.add(label + " is missing sourceIds.");
            return;
        }
        if (sourceIds.stream().anyMatch(sourceId -> !validSourceIds.contains(sourceId))) {
            violations.add(label + " refers to a source outside the current request.");
        }
    }

    private void validateMetricHighlight(MetricHighlight highlight, List<ReportSource> sources,
                                         Set<String> validSourceIds, List<String> violations) {
        requireSourceIds("metric highlight", highlight.summary(), highlight.sourceIds(), validSourceIds, violations);
        boolean matchesSourceMetric = highlight.sourceIds().stream()
                .flatMap(sourceId -> sources.stream().filter(source -> source.sourceId().equals(sourceId)))
                .anyMatch(source -> source.sourceType() == dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType.METRIC
                        && highlight.metricName().equals(source.attributes().get("name"))
                        && highlight.metricValue().equals(source.attributes().get("value"))
                        && highlight.unit().equals(source.attributes().get("unit")));
        if (!matchesSourceMetric) {
            violations.add("Metric highlight does not exactly match a cited metric source.");
        }
    }

    public record ValidationResult(boolean valid, List<String> violations) {
    }
}
