package dev.qcoding.businesscopilot.acceptance;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.readiness.EnterpriseReadiness;
import dev.qcoding.businesscopilot.readiness.EnterpriseReadinessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层验收证据 API（CORE-05）：四类证据分别显示状态，READY 不替代其他验收。
 */
@RestController
@RequestMapping("/api/admin/acceptance-evidence")
public class AcceptanceEvidenceController {

    private final AcceptanceEvidenceService evidenceService;
    private final EnterpriseReadinessService readinessService;

    public AcceptanceEvidenceController(AcceptanceEvidenceService evidenceService,
                                        EnterpriseReadinessService readinessService) {
        this.evidenceService = evidenceService;
        this.readinessService = readinessService;
    }

    /** 四类证据的聚合状态与发布判定。 */
    @GetMapping
    public ResponseEntity<ApiResponse<?>> summaries() {
        Map<String, Object> body = new LinkedHashMap<>();
        AcceptanceEvidence.ReleaseReadiness readiness = evidenceService.releaseReadiness();
        body.put("applicableVersion", evidenceService.currentVersion());
        body.put("categories", readiness.categories());
        body.put("releaseReadiness", readiness);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /** 按类别列出证据明细。 */
    @GetMapping("/{category}")
    public ResponseEntity<ApiResponse<?>> byCategory(
            @org.springframework.web.bind.annotation.PathVariable AcceptanceEvidence.Category category) {
        return ResponseEntity.ok(ApiResponse.ok(evidenceService.byCategory(category)));
    }

    /** 记录一条验收证据（模型质量 / 供应商验收 / 发布门禁）。 */
    @PostMapping
    public ResponseEntity<ApiResponse<?>> record(@Valid @RequestBody EvidenceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(evidenceService.record(
                new AcceptanceEvidence.Evidence(null, request.category(), request.name(),
                        request.status(), request.source(), request.applicableVersion(),
                        request.note(), null, null))));
    }

    /** 把最新运行就绪评估同步为运行就绪证据；其他类别不受影响。 */
    @PostMapping("/runtime-readiness/sync")
    public ResponseEntity<ApiResponse<?>> syncRuntimeReadiness() {
        EnterpriseReadiness.Assessment assessment = readinessService.assess();
        return ResponseEntity.ok(ApiResponse.ok(evidenceService.recordRuntimeAssessment(assessment)));
    }

    public record EvidenceRequest(
            @NotNull AcceptanceEvidence.Category category,
            @NotBlank @Size(max = 100) String name,
            @NotNull AcceptanceEvidence.Status status,
            @NotBlank @Size(max = 200) String source,
            @NotBlank @Size(max = 100) String applicableVersion,
            @Size(max = 1000) String note) {
    }
}
