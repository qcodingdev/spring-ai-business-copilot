package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;

/** Applies a final sensitive-text pass to model output before it reaches an API response. */
public class ReportOutputSanitizer {

    private final SensitiveTextMasker sensitiveTextMasker;

    public ReportOutputSanitizer(SensitiveTextMasker sensitiveTextMasker) {
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    public LlmReportOutput sanitize(LlmReportOutput output) {
        return new LlmReportOutput(mask(output.executiveSummary()), output.executiveSummarySourceIds(),
                output.metricHighlights().stream().map(item -> new MetricHighlight(mask(item.metricName()),
                        mask(item.metricValue()), mask(item.unit()), mask(item.summary()), item.sourceIds())).toList(),
                output.completedItems().stream().map(item -> new ReportItem(mask(item.text()), item.sourceIds())).toList(),
                output.risks().stream().map(item -> new ReportItem(mask(item.text()), item.sourceIds())).toList(),
                output.actionItems().stream().map(item -> new ReportActionItem(item.origin(), mask(item.text()), item.sourceIds())).toList(),
                output.suggestions().stream().map(item -> new ReportActionItem(item.origin(), mask(item.text()), item.sourceIds())).toList(),
                output.citations().stream().map(item -> new ReportCitation(item.sourceId(), mask(item.reason()))).toList());
    }

    private String mask(String value) {
        return value == null ? null : sensitiveTextMasker.mask(value);
    }
}
