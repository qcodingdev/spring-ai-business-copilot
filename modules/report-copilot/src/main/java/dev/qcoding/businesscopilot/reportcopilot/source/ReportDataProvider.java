package dev.qcoding.businesscopilot.reportcopilot.source;

import java.util.List;

/** Narrow boundary for trusted, server-provided report evidence. */
public interface ReportDataProvider {

    List<RawReportSource> loadSources();
}
