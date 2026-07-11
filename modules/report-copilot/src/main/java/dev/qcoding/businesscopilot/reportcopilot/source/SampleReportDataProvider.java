package dev.qcoding.businesscopilot.reportcopilot.source;

import java.util.List;
import java.util.Map;

/**
 * Provides fictional, trusted data for the Report Copilot demo.
 *
 * <p>The source provider is intentionally narrow. Later integrations may implement the same
 * boundary without allowing model-generated or client-provided SQL to enter this module.</p>
 */
public class SampleReportDataProvider implements ReportDataProvider {

    @Override
    public List<RawReportSource> loadSources() {
        return List.of(
                new RawReportSource(ReportSourceType.METRIC, "Weekly business metrics",
                        "Gross merchandise value was CNY 128,400, with 1,284 paid orders, a 1.8% refund rate, and 356 new users.",
                        Map.of("period", "2026-W27", "collectedAt", "2026-07-09T09:00:00Z")),
                new RawReportSource(ReportSourceType.TASK, "Checkout flow monitoring",
                        "Completed: added payment failure monitoring and alert routing. Owner: Product Team.",
                        Map.of("status", "COMPLETED", "source", "weekly task board")),
                new RawReportSource(ReportSourceType.TASK, "Mobile release validation",
                        "Blocked: mobile release validation is waiting for the external sandbox to recover. Owner: Delivery Team.",
                        Map.of("status", "BLOCKED", "source", "weekly task board")),
                new RawReportSource(ReportSourceType.MEETING_NOTE, "Weekly delivery sync",
                        "Decision: keep the staged rollout at 20% until payment failure monitoring has seven days of stable data. Action: review the rollout metric next Tuesday.",
                        Map.of("recordedAt", "2026-07-08T10:00:00Z", "source", "weekly delivery sync")));
    }
}
