package dev.qcoding.businesscopilot.reportcopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.reportcopilot.enterprise.ReportEnterpriseService;
import dev.qcoding.businesscopilot.reportcopilot.export.ReportOfficeExportService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Report 企业多来源、定时草稿和办公格式导出 API。 */
@RestController
@RequestMapping("/api/report-copilot/enterprise")
public class ReportEnterpriseController {

    private final ReportEnterpriseService service;
    private final ReportOfficeExportService exportService;

    public ReportEnterpriseController(
            ReportEnterpriseService service,
            ReportOfficeExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<?>> connections() {
        return ResponseEntity.ok(ApiResponse.ok(service.connections()));
    }

    @PostMapping("/connections")
    public ResponseEntity<ApiResponse<?>> saveConnection(
            @Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveConnection(
                new ReportEnterpriseService.ConnectionCommand(
                        request.connectionKey(), request.displayName(), request.provider(),
                        request.baseUrl(), request.secretRef(), request.enabled()))));
    }

    @PostMapping("/reports/generate")
    public ResponseEntity<ApiResponse<?>> generate(@Valid @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.generate(
                new ReportEnterpriseService.GenerateCommand(
                        request.reportType(), request.period(), request.title(),
                        request.selection(), request.templateId(), request.templateVersion()))));
    }

    @GetMapping("/schedules")
    public ResponseEntity<ApiResponse<?>> schedules() {
        return ResponseEntity.ok(ApiResponse.ok(service.schedules()));
    }

    @GetMapping("/schedules/{scheduleId}/runs")
    public ResponseEntity<ApiResponse<?>> scheduleRuns(@PathVariable long scheduleId) {
        return ResponseEntity.ok(ApiResponse.ok(service.scheduleRuns(scheduleId)));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<?>> records() {
        return ResponseEntity.ok(ApiResponse.ok(service.records()));
    }

    @PostMapping("/schedules")
    public ResponseEntity<ApiResponse<?>> saveSchedule(
            @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveSchedule(
                new ReportEnterpriseService.ScheduleCommand(
                        request.scheduleKey(), request.reportType(), request.titleTemplate(),
                        request.cronExpression(), request.zoneId(), request.templateId(),
                        request.templateVersion(), request.selection(), request.enabled()))));
    }

    @GetMapping("/reports/{draftId}/docx")
    public ResponseEntity<byte[]> docx(@PathVariable long draftId) {
        return download("report-" + draftId + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                exportService.exportDocx(draftId));
    }

    @GetMapping("/reports/{draftId}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long draftId) {
        return download("report-" + draftId + ".pdf", "application/pdf",
                exportService.exportPdf(draftId));
    }

    @GetMapping("/reports/{draftId}/pptx")
    public ResponseEntity<byte[]> pptx(@PathVariable long draftId) {
        return download("report-" + draftId + ".pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                exportService.exportPptx(draftId));
    }

    private ResponseEntity<byte[]> download(String fileName, String mediaType, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(mediaType))
                .body(content);
    }

    public record ConnectionRequest(
            @NotBlank @Size(max = 100) String connectionKey,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull ReportEnterpriseService.Provider provider,
            @Size(max = 500) String baseUrl,
            @Size(max = 200) String secretRef,
            boolean enabled) { }
    public record GenerateRequest(
            @NotNull ReportType reportType,
            @NotNull ReportPeriod period,
            @NotBlank @Size(max = 300) String title,
            @NotNull ReportEnterpriseService.SourceSelection selection,
            @NotBlank @Size(max = 100) String templateId,
            @NotBlank @Size(max = 40) String templateVersion) { }
    public record ScheduleRequest(
            @NotBlank @Size(max = 100) String scheduleKey,
            @NotNull ReportType reportType,
            @NotBlank @Size(max = 300) String titleTemplate,
            @NotBlank @Size(max = 100) String cronExpression,
            @NotBlank @Size(max = 80) String zoneId,
            @NotBlank @Size(max = 100) String templateId,
            @NotBlank @Size(max = 40) String templateVersion,
            @NotNull ReportEnterpriseService.SourceSelection selection,
            boolean enabled) { }
}
