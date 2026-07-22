package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportGroundingEvaluationTest {

    @Test
    void fixedReportGroundingSetRemainsStable() throws Exception {
        var resource = getClass().getResourceAsStream("/evals/report-grounding.tsv");
        assertThat(resource).isNotNull();
        List<String> lines = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertThat(lines).as("Report 固定评测集不能缩减到 10 条以下").hasSizeGreaterThanOrEqualTo(10);
        ReportSource source = new ReportSource(
                "source-1", ReportSourceType.METRIC, "Orders", "Orders: 1284",
                "a".repeat(64), Map.of("name", "Orders", "value", "1284", "unit", "orders"));
        ReportGenerationOutputValidator validator = new ReportGenerationOutputValidator();

        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            boolean expected = Boolean.parseBoolean(fields[1]);
            assertThat(validator.validate(output(fields[0]), List.of(source)).valid())
                    .as(line).isEqualTo(expected);
        }
    }

    private LlmReportOutput output(String type) {
        String summary = "valid_empty_summary".equals(type) ? "" : "Orders remained stable.";
        List<String> summarySources = switch (type) {
            case "unknown_summary_source" -> List.of("other");
            case "missing_summary_source" -> List.of();
            default -> List.of("source-1");
        };
        String metricValue = "invented_metric_value".equals(type) ? "9999" : "1284";
        List<String> metricSources = switch (type) {
            case "unknown_metric_source" -> List.of("other");
            case "missing_metric_source" -> List.of();
            default -> List.of("source-1");
        };
        List<ReportActionItem> sourceActions = "source_action_wrong_origin".equals(type)
                ? List.of(new ReportActionItem(ReportActionItemOrigin.AI_SUGGESTION,
                "Follow up", List.of("source-1"))) : List.of();
        List<ReportActionItem> suggestions = switch (type) {
            case "suggestion_claims_source" -> List.of(new ReportActionItem(
                    ReportActionItemOrigin.AI_SUGGESTION, "Investigate growth", List.of("source-1")));
            case "suggestion_wrong_origin" -> List.of(new ReportActionItem(
                    ReportActionItemOrigin.SOURCE_ACTION, "Investigate growth", List.of()));
            case "valid_suggestion" -> List.of(new ReportActionItem(
                    ReportActionItemOrigin.AI_SUGGESTION, "Investigate growth", List.of()));
            default -> List.of();
        };
        List<ReportCitation> citations = "unknown_citation".equals(type)
                ? List.of(new ReportCitation("other", "metric"))
                : List.of(new ReportCitation("source-1", "metric"));
        return new LlmReportOutput(
                summary, summarySources,
                List.of(new MetricHighlight(
                        "Orders", metricValue, "orders", "Orders remained stable.", metricSources)),
                List.of(), List.of(), sourceActions, suggestions, citations);
    }
}
