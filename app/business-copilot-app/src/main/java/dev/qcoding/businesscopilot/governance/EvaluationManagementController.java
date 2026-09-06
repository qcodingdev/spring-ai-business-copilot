package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/** Managed evaluation datasets, versions, cases, runs, gates and report exports. */
@RestController
@RequestMapping("/api/governance/evaluations")
public class EvaluationManagementController {

    private final EvaluationManagementService service;
    private final ObjectMapper objectMapper;

    public EvaluationManagementController(EvaluationManagementService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/datasets")
    public ResponseEntity<ApiResponse<?>> datasets() {
        return ResponseEntity.ok(ApiResponse.ok(service.datasets()));
    }

    @PostMapping("/datasets")
    public ResponseEntity<ApiResponse<?>> createDataset(
            @Valid @RequestBody DatasetRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.createDataset(
                new EvaluationManagementService.DatasetCommand(
                        request.datasetKey(), request.moduleKey(), request.nameZh(), request.nameEn(),
                        request.descriptionZh(), request.descriptionEn()))));
    }

    @PostMapping("/datasets/{datasetId}/archive")
    public ResponseEntity<ApiResponse<?>> archiveDataset(@PathVariable long datasetId) {
        return ResponseEntity.ok(ApiResponse.ok(service.archiveDataset(datasetId)));
    }

    @PostMapping("/versions/{versionId}/clone")
    public ResponseEntity<ApiResponse<?>> cloneVersion(
            @PathVariable long versionId, @Valid @RequestBody NoteRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.cloneVersion(versionId, request.note())));
    }

    @PostMapping("/versions/{versionId}/cases")
    public ResponseEntity<ApiResponse<?>> createCase(
            @PathVariable long versionId,
            @RequestBody EvaluationManagementService.CaseCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveCase(versionId, null, request)));
    }

    @PutMapping("/versions/{versionId}/cases/{caseId}")
    public ResponseEntity<ApiResponse<?>> updateCase(
            @PathVariable long versionId, @PathVariable long caseId,
            @RequestBody EvaluationManagementService.CaseCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveCase(versionId, caseId, request)));
    }

    @PostMapping("/versions/{versionId}/cases/import")
    public ResponseEntity<ApiResponse<?>> importCases(
            @PathVariable long versionId,
            @RequestBody @Size(min = 1, max = 500) List<EvaluationManagementService.CaseCommand> request) {
        return ResponseEntity.ok(ApiResponse.ok(service.importCases(versionId, request)));
    }

    @PostMapping("/versions/{versionId}/cases/{caseId}/enabled")
    public ResponseEntity<ApiResponse<?>> setCaseEnabled(
            @PathVariable long versionId, @PathVariable long caseId,
            @RequestBody EnabledRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.setCaseEnabled(versionId, caseId, request.enabled())));
    }

    @GetMapping(value = "/versions/{versionId}/export", produces = "text/csv")
    public ResponseEntity<String> exportCases(@PathVariable long versionId) {
        EvaluationManagementService.VersionView version = service.datasets().stream()
                .flatMap(dataset -> dataset.versions().stream())
                .filter(item -> item.id() == versionId).findFirst()
                .orElseThrow(() -> new dev.qcoding.businesscopilot.commonweb.api.BusinessException(
                        dev.qcoding.businesscopilot.commonweb.api.ErrorCode.NOT_FOUND));
        StringBuilder csv = new StringBuilder("caseKey,titleZh,titleEn,executionType,promptKey,variablesJson,expectedJson,forbiddenJson,critical,enabled,maxLatencyMs,maxModelCalls\n");
        for (EvaluationManagementService.CaseView item : version.cases()) {
            csv.append(csv(item.caseKey())).append(',').append(csv(item.titleZh())).append(',')
                    .append(csv(item.titleEn())).append(',').append(item.executionType()).append(',')
                    .append(csv(item.promptKey())).append(',').append(csv(json(item.variables()))).append(',')
                    .append(csv(json(item.expected()))).append(',').append(csv(json(item.forbidden()))).append(',')
                    .append(item.critical()).append(',')
                    .append(item.enabled()).append(',').append(item.maxLatencyMs() == null ? "" : item.maxLatencyMs())
                    .append(',').append(item.maxModelCalls() == null ? "" : item.maxModelCalls()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=evaluation-version-" + versionId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString());
    }

    @GetMapping(value = "/versions/{versionId}/export.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportCasesJson(@PathVariable long versionId) {
        EvaluationManagementService.VersionView version = service.datasets().stream()
                .flatMap(dataset -> dataset.versions().stream())
                .filter(item -> item.id() == versionId).findFirst()
                .orElseThrow(() -> new dev.qcoding.businesscopilot.commonweb.api.BusinessException(
                        dev.qcoding.businesscopilot.commonweb.api.ErrorCode.NOT_FOUND));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=evaluation-version-" + versionId + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json(version.cases()));
    }

    @PostMapping("/versions/{versionId}/submit")
    public ResponseEntity<ApiResponse<?>> submitVersion(@PathVariable long versionId) {
        return ResponseEntity.ok(ApiResponse.ok(service.submitVersion(versionId)));
    }

    @PostMapping("/versions/{versionId}/review")
    public ResponseEntity<ApiResponse<?>> reviewVersion(
            @PathVariable long versionId, @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.reviewVersion(versionId, request.approve(), request.note())));
    }

    @PostMapping("/versions/{versionId}/publish")
    public ResponseEntity<ApiResponse<?>> publishVersion(@PathVariable long versionId) {
        return ResponseEntity.ok(ApiResponse.ok(service.publishVersion(versionId)));
    }

    @GetMapping("/gate-policies")
    public ResponseEntity<ApiResponse<?>> gatePolicies() {
        return ResponseEntity.ok(ApiResponse.ok(service.gatePolicies()));
    }

    @PutMapping("/gate-policies/{moduleKey}")
    public ResponseEntity<ApiResponse<?>> saveGatePolicy(
            @PathVariable String moduleKey,
            @RequestBody GatePolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveGatePolicy(
                new EvaluationManagementService.GatePolicyCommand(
                        moduleKey, request.minimumPassRate(), request.requireCriticalPass(),
                        request.maximumAverageLatency(), request.maximumTotalTokens()))));
    }

    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<?>> runs() {
        return ResponseEntity.ok(ApiResponse.ok(service.runs()));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ApiResponse<?>> run(@PathVariable UUID runId) {
        return ResponseEntity.ok(ApiResponse.ok(service.run(runId)));
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<?>> startRun(@Valid @RequestBody RunRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.startRun(
                new EvaluationManagementService.RunCommand(
                        request.versionId(), request.promptVersionId(), request.environment(),
                        request.idempotencyKey()))));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<ApiResponse<?>> cancelRun(@PathVariable UUID runId) {
        return ResponseEntity.ok(ApiResponse.ok(service.cancelRun(runId)));
    }

    @PostMapping("/runs/{runId}/external-results")
    public ResponseEntity<ApiResponse<?>> externalResults(
            @PathVariable UUID runId,
            @RequestBody @Size(min = 1, max = 500) List<EvaluationManagementService.ExternalResultCommand> request) {
        return ResponseEntity.ok(ApiResponse.ok(service.recordExternalResults(runId, request)));
    }

    @GetMapping(value = "/runs/{runId}/report", produces = "text/markdown")
    public ResponseEntity<String> report(@PathVariable UUID runId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=evaluation-report-" + runId + ".md")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(service.markdownReport(runId));
    }

    private static String csv(Object value) {
        if (value == null) return "";
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new dev.qcoding.businesscopilot.commonweb.api.BusinessException(
                    dev.qcoding.businesscopilot.commonweb.api.ErrorCode.VALIDATION_ERROR);
        }
    }

    public record DatasetRequest(
            @NotBlank @Size(max = 100) String datasetKey,
            @NotBlank @Size(max = 40) String moduleKey,
            @NotBlank @Size(max = 200) String nameZh,
            @NotBlank @Size(max = 200) String nameEn,
            @Size(max = 1000) String descriptionZh,
            @Size(max = 1000) String descriptionEn) { }
    public record NoteRequest(@NotBlank @Size(max = 1000) String note) { }
    public record ReviewRequest(boolean approve, @NotBlank @Size(max = 1000) String note) { }
    public record EnabledRequest(boolean enabled) { }
    public record GatePolicyRequest(double minimumPassRate, boolean requireCriticalPass,
                                    Long maximumAverageLatency, Long maximumTotalTokens) { }
    public record RunRequest(long versionId, Long promptVersionId,
                             @NotNull EvaluationManagementService.Environment environment,
                             @NotBlank @Size(max = 100) String idempotencyKey) { }
}
