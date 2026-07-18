package dev.qcoding.businesscopilot.reportcopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportDraftResponse;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftConfirmationService;
import dev.qcoding.businesscopilot.reportcopilot.export.ReportMarkdownExportService;
import dev.qcoding.businesscopilot.reportcopilot.export.ReportHtmlExportService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceImportService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** REST entrypoint for the Report Copilot source-preview feature. */
@RestController
@RequestMapping("/api/report-copilot")
@ConditionalOnProperty(prefix = "business-copilot.report-copilot", name = "enabled", havingValue = "true")
public class ReportCopilotController {

    private final ReportSourcePreviewService sourcePreviewService;
    private final ReportRequestPreparationService requestPreparationService;
    private final ReportSourceImportService sourceImportService;
    private final ReportGenerationService generationService;
    private final ReportDraftConfirmationService confirmationService;
    private final ReportMarkdownExportService markdownExportService;
    private final ReportHtmlExportService htmlExportService;

    public ReportCopilotController(ReportSourcePreviewService sourcePreviewService,
                                   ReportRequestPreparationService requestPreparationService,
                                   ReportSourceImportService sourceImportService,
                                   ReportGenerationService generationService,
                                   ReportDraftConfirmationService confirmationService,
                                   ReportMarkdownExportService markdownExportService,
                                   ReportHtmlExportService htmlExportService) {
        this.sourcePreviewService = sourcePreviewService;
        this.requestPreparationService = requestPreparationService;
        this.sourceImportService = sourceImportService;
        this.generationService = generationService;
        this.confirmationService = confirmationService;
        this.markdownExportService = markdownExportService;
        this.htmlExportService = htmlExportService;
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

    /** Parses and sanitizes a bounded CSV/JSON file without invoking a model. */
    @PostMapping(path = "/source-imports/preview", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<ReportSourceImportService.ImportPreview>> previewImport(
            @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(ApiResponse.ok(sourceImportService.preview(
                file.getOriginalFilename(), file.getContentType(), file.getBytes())));
    }

    /** Generates a structured review-only report candidate from validated evidence. */
    @PostMapping("/reports/generate")
    public ResponseEntity<ApiResponse<ReportDraftResponse>> generateReport(
            @Valid @RequestBody ReportGenerateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(generationService.generate(request)));
    }

    /** Generates a report directly from a bounded CSV/JSON business source file. */
    @PostMapping(path = "/reports/generate-from-file", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<ReportDraftResponse>> generateReportFromFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam ReportType reportType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam String title,
            @RequestParam(required = false) String templateId,
            @RequestParam(required = false) String templateVersion) throws IOException {
        var importedSources = sourceImportService.parse(
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        ReportGenerateRequest request = new ReportGenerateRequest(
                reportType, new ReportPeriod(periodStart, periodEnd), title,
                List.of(), List.of(), List.of(), importedSources, templateId, templateVersion);
        return ResponseEntity.ok(ApiResponse.ok(generationService.generate(request)));
    }

    /** Confirms a server-generated DRAFTED report. Confirmation does not publish it anywhere. */
    @PostMapping("/reports/{draftId}/confirm")
    public ResponseEntity<ApiResponse<ReportDraftConfirmationService.ConfirmationResult>> confirmReport(
            @PathVariable Long draftId, @Valid @RequestBody ConfirmationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(confirmationService.confirm(draftId, request.confirmationToken())));
    }

    /** Cancels a server-generated DRAFTED or NEEDS_REVIEW report and invalidates its token. */
    @PostMapping("/reports/{draftId}/cancel")
    public ResponseEntity<ApiResponse<ReportDraftConfirmationService.ConfirmationResult>> cancelReport(
            @PathVariable Long draftId, @Valid @RequestBody ConfirmationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(confirmationService.cancel(draftId, request.confirmationToken())));
    }

    /** Exports only a confirmed draft as server-rendered Markdown. */
    @GetMapping(value = "/reports/{draftId}/markdown", produces = "text/markdown")
    public ResponseEntity<String> exportMarkdown(@PathVariable Long draftId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + draftId + ".md")
                .contentType(MediaType.parseMediaType("text/markdown"))
                .body(markdownExportService.export(draftId));
    }

    /** Exports only a confirmed draft as deterministic, escaped HTML. */
    @GetMapping(value = "/reports/{draftId}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> exportHtml(@PathVariable Long draftId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + draftId + ".html")
                .contentType(MediaType.TEXT_HTML)
                .body(htmlExportService.export(draftId));
    }

    public record ConfirmationRequest(
            @jakarta.validation.constraints.NotBlank(message = "确认凭证不能为空。") String confirmationToken) {
    }
}
