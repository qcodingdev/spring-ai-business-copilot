package dev.qcoding.businesscopilot.reportcopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportDraftResponse;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftConfirmationService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ReportDraftConfirmationService confirmationService;

    public ReportCopilotController(ReportSourcePreviewService sourcePreviewService,
                                   ReportRequestPreparationService requestPreparationService,
                                   ReportGenerationService generationService,
                                   ReportDraftConfirmationService confirmationService) {
        this.sourcePreviewService = sourcePreviewService;
        this.requestPreparationService = requestPreparationService;
        this.generationService = generationService;
        this.confirmationService = confirmationService;
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

    /** Confirms a server-generated DRAFTED report. Confirmation does not publish it anywhere. */
    @PostMapping("/reports/{draftId}/confirm")
    public ResponseEntity<ApiResponse<ReportDraftConfirmationService.ConfirmationResult>> confirmReport(
            @PathVariable Long draftId, @Valid @RequestBody ConfirmationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(confirmationService.confirm(draftId, request.confirmationToken())));
    }

    /** Cancels a server-generated DRAFTED report and invalidates its confirmation token. */
    @PostMapping("/reports/{draftId}/cancel")
    public ResponseEntity<ApiResponse<ReportDraftConfirmationService.ConfirmationResult>> cancelReport(
            @PathVariable Long draftId, @Valid @RequestBody ConfirmationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(confirmationService.cancel(draftId, request.confirmationToken())));
    }

    public record ConfirmationRequest(
            @jakarta.validation.constraints.NotBlank(message = "confirmationToken is required") String confirmationToken) {
    }
}
