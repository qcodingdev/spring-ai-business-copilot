package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 私有 Admin 的初始化、任务查询和双确认恢复接口。 */
@RestController
@RequestMapping("/api/admin/demo-data")
public class DemoAdminController {

    private final DemoDataInitializationService initializationService;
    private final DemoDataResetService resetService;

    public DemoAdminController(
            DemoDataInitializationService initializationService,
            DemoDataResetService resetService) {
        this.initializationService = initializationService;
        this.resetService = resetService;
    }

    @PostMapping("/initialize")
    public ResponseEntity<ApiResponse<DemoDataJob>> initialize() {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(initializationService.initialize(), "初始化任务已创建"));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<DemoDataJob>> job(@PathVariable UUID jobId) {
        DemoDataJob job = initializationService.getJob(jobId);
        return job == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(ApiResponse.ok(job));
    }

    @PostMapping("/reset-intents")
    public ResponseEntity<ApiResponse<DemoDataResetService.ResetIntent>> resetIntent() {
        return ResponseEntity.ok(ApiResponse.ok(resetService.createIntent()));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<DemoDataJob>> reset(
            @Valid @RequestBody ResetRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                resetService.reset(request.resetToken(), request.confirmationText())));
    }

    public record ResetRequest(
            @NotBlank(message = "恢复凭证不能为空。") String resetToken,
            @NotBlank(message = "恢复确认文案不能为空。") String confirmationText) {
    }
}
