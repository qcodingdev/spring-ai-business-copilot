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
        String summarySource = "unknown_summary_source".equals(type) ? "other" : "source-1";
        String metricValue = "invented_metric_value".equals(type) ? "9999" : "1284";
        List<ReportActionItem> suggestions = "suggestion_claims_source".equals(type)
                ? List.of(new ReportActionItem(
                        ReportActionItemOrigin.AI_SUGGESTION, "Investigate growth", List.of("source-1")))
                : List.of();
        return new LlmReportOutput(
                "Orders remained stable.", List.of(summarySource),
                List.of(new MetricHighlight(
                        "Orders", metricValue, "orders", "Orders remained stable.", List.of("source-1"))),
                List.of(), List.of(), List.of(), suggestions,
                List.of(new ReportCitation("source-1", "metric")));
    }
}
