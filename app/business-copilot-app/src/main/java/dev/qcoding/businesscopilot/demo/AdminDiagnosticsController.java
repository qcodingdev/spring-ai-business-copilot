package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 私有管理台只读诊断接口。 */
@RestController
@RequestMapping("/api/admin")
public class AdminDiagnosticsController {

    private final AdminDiagnosticsService diagnosticsService;

    public AdminDiagnosticsController(AdminDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<ApiResponse<AdminDiagnosticsService.Diagnostics>> diagnostics() {
        return ResponseEntity.ok(ApiResponse.ok(diagnosticsService.diagnostics()));
    }
}
