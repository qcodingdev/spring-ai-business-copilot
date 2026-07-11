package dev.qcoding.businesscopilot.reportcopilot.generation;

import java.util.List;

/** A factual report item with the evidence that supports it. */
public record ReportItem(String text, List<String> sourceIds) {
    public ReportItem {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
