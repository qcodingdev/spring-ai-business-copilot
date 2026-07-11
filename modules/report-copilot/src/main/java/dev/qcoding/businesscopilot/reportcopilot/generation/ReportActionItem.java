package dev.qcoding.businesscopilot.reportcopilot.generation;

import java.util.List;

/** A source action or AI suggestion, kept separate to prevent suggestions from appearing as facts. */
public record ReportActionItem(ReportActionItemOrigin origin, String text, List<String> sourceIds) {
    public ReportActionItem {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
