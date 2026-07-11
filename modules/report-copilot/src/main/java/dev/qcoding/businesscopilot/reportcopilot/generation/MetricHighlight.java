package dev.qcoding.businesscopilot.reportcopilot.generation;

import java.util.List;

/** A metric restatement grounded in one or more metric sources. */
public record MetricHighlight(String metricName, String metricValue, String unit, String summary,
                              List<String> sourceIds) {
    public MetricHighlight {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
