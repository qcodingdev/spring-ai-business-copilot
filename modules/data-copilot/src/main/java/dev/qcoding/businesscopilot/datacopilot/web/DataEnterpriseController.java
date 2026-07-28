package dev.qcoding.businesscopilot.datacopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataGovernanceService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataQueryResultService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** Data Copilot 企业治理与受控交付 API。 */
@RestController
@RequestMapping("/api/data-copilot")
public class DataEnterpriseController {

    private final DataGovernanceService governanceService;
    private final DataQueryResultService resultService;
    private final QueryExecutionService executionService;

    public DataEnterpriseController(DataGovernanceService governanceService,
                                    DataQueryResultService resultService,
                                    QueryExecutionService executionService) {
        this.governanceService = governanceService;
        this.resultService = resultService;
        this.executionService = executionService;
    }

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<?>> metrics() {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.metrics()));
    }

    @PostMapping("/metrics")
    public ResponseEntity<ApiResponse<?>> saveMetric(@Valid @RequestBody MetricRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.saveMetric(
                new DataGovernanceService.MetricCommand(request.metricKey(), request.displayName(),
                        request.description(), request.unit(), request.expressionSql()))));
    }

    @PostMapping("/metrics/{id}/approve")
    public ResponseEntity<ApiResponse<?>> approveMetric(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.approveMetric(id)));
    }

    @GetMapping("/query-templates")
    public ResponseEntity<ApiResponse<?>> templates() {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.templates()));
    }

    @PostMapping("/query-templates")
    public ResponseEntity<ApiResponse<?>> saveTemplate(@Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.saveTemplate(
                new DataGovernanceService.TemplateCommand(request.templateKey(), request.name(),
                        request.description(), request.sql()))));
    }

    @PostMapping("/query-templates/{id}/approve")
    public ResponseEntity<ApiResponse<?>> approveTemplate(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.approveTemplate(id)));
    }

    @PostMapping("/query-templates/{id}/launch")
    public ResponseEntity<ApiResponse<?>> launchTemplate(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.launchTemplate(id)));
    }

    @GetMapping("/datasource-health")
    public ResponseEntity<ApiResponse<?>> health() {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.health()));
    }

    @PostMapping("/schema-change-check")
    public ResponseEntity<ApiResponse<?>> checkSchema() {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.checkSchema()));
    }

    @PostMapping("/query-cost-preview")
    public ResponseEntity<ApiResponse<?>> previewCost(@Valid @RequestBody SqlRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(governanceService.previewCost(request.sql())));
    }

    @PostMapping("/executions/{executionId}/cancel")
    public ResponseEntity<ApiResponse<Boolean>> cancel(@PathVariable String executionId) {
        return ResponseEntity.ok(ApiResponse.ok(executionService.cancel(executionId)));
    }

    @GetMapping("/query-results/{resultId}/csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable long resultId) {
        return download("query-result-" + resultId + ".csv", "text/csv",
                resultService.exportCsv(resultId));
    }

    @GetMapping("/query-results/{resultId}/xlsx")
    public ResponseEntity<byte[]> exportXlsx(@PathVariable long resultId) {
        return download("query-result-" + resultId + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                resultService.exportXlsx(resultId));
    }

    @PostMapping("/query-results/{resultId}/report-handoff")
    public ResponseEntity<ApiResponse<?>> handoff(
            @PathVariable long resultId, @Valid @RequestBody HandoffRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(resultService.createReportHandoff(resultId, request.title())));
    }

    private ResponseEntity<byte[]> download(String fileName, String contentType, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }

    public record MetricRequest(
            @NotBlank @Size(max = 100) String metricKey,
            @NotBlank @Size(max = 200) String displayName,
            @NotBlank @Size(max = 1000) String description,
            @Size(max = 50) String unit,
            @NotBlank @Size(max = 10000) String expressionSql) { }
    public record TemplateRequest(
            @NotBlank @Size(max = 100) String templateKey,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 1000) String description,
            @NotBlank @Size(max = 10000) String sql) { }
    public record SqlRequest(@NotBlank @Size(max = 10000) String sql) { }
    public record HandoffRequest(@NotBlank @Size(max = 300) String title) { }
}
