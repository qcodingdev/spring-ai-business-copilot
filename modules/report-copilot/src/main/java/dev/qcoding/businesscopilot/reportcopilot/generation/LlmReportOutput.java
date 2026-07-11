package dev.qcoding.businesscopilot.reportcopilot.generation;

import java.util.List;

/** Structured model output for Report Copilot. It is validated before being returned to the client. */
public record LlmReportOutput(
        String executiveSummary,
        List<String> executiveSummarySourceIds,
        List<MetricHighlight> metricHighlights,
        List<ReportItem> completedItems,
        List<ReportItem> risks,
        List<ReportActionItem> actionItems,
        List<ReportActionItem> suggestions,
        List<ReportCitation> citations) {

    public LlmReportOutput {
        executiveSummarySourceIds = executiveSummarySourceIds == null ? List.of() : List.copyOf(executiveSummarySourceIds);
        metricHighlights = metricHighlights == null ? List.of() : List.copyOf(metricHighlights);
        completedItems = completedItems == null ? List.of() : List.copyOf(completedItems);
        risks = risks == null ? List.of() : List.copyOf(risks);
        actionItems = actionItems == null ? List.of() : List.copyOf(actionItems);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
