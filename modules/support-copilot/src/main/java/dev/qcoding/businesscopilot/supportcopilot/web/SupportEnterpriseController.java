package dev.qcoding.businesscopilot.supportcopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportEnterpriseService;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportExternalProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Support Copilot 企业工单连接、SLA、相似工单和受控回写 API。 */
@RestController
@RequestMapping("/api/support-copilot/enterprise")
public class SupportEnterpriseController {

    private final SupportEnterpriseService service;

    public SupportEnterpriseController(SupportEnterpriseService service) {
        this.service = service;
    }

    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<?>> connections() {
        return ResponseEntity.ok(ApiResponse.ok(service.connections()));
    }

    @PostMapping("/connections")
    public ResponseEntity<ApiResponse<?>> save(@Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.save(
                new SupportEnterpriseService.ConnectionCommand(
                        request.connectionKey(), request.displayName(), request.provider(),
                        request.baseUrl(), request.secretRef(), request.enabled()))));
    }

    @PostMapping("/connections/{connectionId}/import")
    public ResponseEntity<ApiResponse<?>> importRecent(
            @PathVariable long connectionId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(ApiResponse.ok(service.importRecent(connectionId, limit)));
    }

    @GetMapping("/tickets/{ticketId}/similar")
    public ResponseEntity<ApiResponse<?>> similar(
            @PathVariable long ticketId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ResponseEntity.ok(ApiResponse.ok(service.similar(ticketId, limit)));
    }

    @PostMapping("/sla/refresh")
    public ResponseEntity<ApiResponse<Integer>> refreshSla() {
        return ResponseEntity.ok(ApiResponse.ok(service.refreshSla()));
    }

    @GetMapping("/quality-metrics")
    public ResponseEntity<ApiResponse<?>> metrics() {
        return ResponseEntity.ok(ApiResponse.ok(service.metrics()));
    }

    @PostMapping("/drafts/{draftId}/writeback-intent")
    public ResponseEntity<ApiResponse<?>> prepareWriteback(@PathVariable long draftId) {
        return ResponseEntity.ok(ApiResponse.ok(service.prepareWriteback(draftId)));
    }

    @PostMapping("/writebacks/{writebackId}/confirm")
    public ResponseEntity<ApiResponse<?>> confirmWriteback(
            @PathVariable long writebackId, @Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.confirmWriteback(writebackId, request.confirmationToken())));
    }

    public record ConnectionRequest(
            @NotBlank @Size(max = 100) String connectionKey,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull SupportExternalProvider provider,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 200) String secretRef,
            boolean enabled) { }
    public record TokenRequest(@NotBlank String confirmationToken) { }
}
