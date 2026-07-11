package dev.qcoding.businesscopilot.reportcopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportDraftResponse;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entrypoint for the Report Copilot source-preview feature. */
@RestController
@RequestMapping("/api/report-copilot")
@ConditionalOnProperty(prefix = "business-copilot.report-copilot", name = "enabled", havingValue = "true")
public class ReportCopilotController {

    private final ReportSourcePreviewService sourcePreviewService;
    private final ReportRequestPreparationService requestPreparationService;
    private final ReportGenerationService generationService;

    public ReportCopilotController(ReportSourcePreviewService sourcePreviewService,
                                   ReportRequestPreparationService requestPreparationService,
                                   ReportGenerationService generationService) {
        this.sourcePreviewService = sourcePreviewService;
        this.requestPreparationService = requestPreparationService;
        this.generationService = generationService;
    }

    /** Returns sanitized fictional metrics, tasks, and meeting notes for a report preview. */
    @GetMapping("/sample-sources")
    public ResponseEntity<ApiResponse<ReportSourcePreviewService.ReportSourcePreview>> sampleSources() {
        return ResponseEntity.ok(ApiResponse.ok(sourcePreviewService.preview()));
    }

    /** Validates and normalizes client-provided evidence without invoking a model. */
    @PostMapping("/source-previews")
    public ResponseEntity<ApiResponse<ReportRequestPreparationService.ReportRequestPreview>> prepareSources(
            @Valid @RequestBody ReportGenerateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(requestPreparationService.prepare(request)));
    }

    /** Generates a structured review-only report candidate from validated evidence. */
    @PostMapping("/reports/generate")
    public ResponseEntity<ApiResponse<ReportDraftResponse>> generateReport(
            @Valid @RequestBody ReportGenerateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(generationService.generate(request)));
    }
}
