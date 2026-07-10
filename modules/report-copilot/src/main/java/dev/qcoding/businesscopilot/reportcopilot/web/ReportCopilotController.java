package dev.qcoding.businesscopilot.reportcopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entrypoint for the Report Copilot source-preview feature. */
@RestController
@RequestMapping("/api/report-copilot")
@ConditionalOnProperty(prefix = "business-copilot.report-copilot", name = "enabled", havingValue = "true")
public class ReportCopilotController {

    private final ReportSourcePreviewService sourcePreviewService;

    public ReportCopilotController(ReportSourcePreviewService sourcePreviewService) {
        this.sourcePreviewService = sourcePreviewService;
    }

    /** Returns sanitized fictional metrics, tasks, and meeting notes for a report preview. */
    @GetMapping("/sample-sources")
    public ResponseEntity<ApiResponse<ReportSourcePreviewService.ReportSourcePreview>> sampleSources() {
        return ResponseEntity.ok(ApiResponse.ok(sourcePreviewService.preview()));
    }
}
