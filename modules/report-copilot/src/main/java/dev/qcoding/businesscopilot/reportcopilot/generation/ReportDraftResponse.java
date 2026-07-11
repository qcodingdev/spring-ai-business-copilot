package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;

import java.util.List;

/** API view of a persisted report draft. NEEDS_REVIEW never exposes untrusted model content. */
public record ReportDraftResponse(Long draftId, ReportType reportType, ReportPeriod period, String title, String status,
                                  LlmReportOutput content, List<String> reviewReasons, String confirmationToken,
                                  String expiresAt, String modelName) {
}
