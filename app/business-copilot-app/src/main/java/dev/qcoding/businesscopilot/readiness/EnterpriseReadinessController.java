package dev.qcoding.businesscopilot.readiness;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.commonweb.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only live assessment, evidence creation and retention-bounded append-only history. */
@Validated
@RestController
@RequestMapping("/api/admin/readiness")
public class EnterpriseReadinessController {

    private final EnterpriseReadinessService service;

    public EnterpriseReadinessController(EnterpriseReadinessService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<EnterpriseReadiness.Assessment>> assess() {
        return ResponseEntity.ok(ApiResponse.ok(service.assess()));
    }

    @PostMapping("/snapshots")
    public ResponseEntity<ApiResponse<EnterpriseReadiness.Snapshot>> createSnapshot(
            @Valid @RequestBody SnapshotRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.createSnapshot(request.purpose())));
    }

    @GetMapping("/snapshots")
    public ResponseEntity<ApiResponse<PageResponse<EnterpriseReadiness.Snapshot>>> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.history(page, size)));
    }

    public record SnapshotRequest(@NotBlank @Size(max = 200) String purpose) {
    }
}
