package dev.qcoding.businesscopilot.knowledgecopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeVisibilityScope;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceProvider;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceSyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 企业知识来源配置、同步和过期冲突 API。 */
@RestController
@RequestMapping("/api/knowledge-copilot/sources")
public class KnowledgeSourceController {

    private final KnowledgeSourceSyncService service;

    public KnowledgeSourceController(KnowledgeSourceSyncService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> connections() {
        return ResponseEntity.ok(ApiResponse.ok(service.connections()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<?>> save(@Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.save(
                new KnowledgeSourceSyncService.ConnectionCommand(
                        request.connectionKey(), request.displayName(), request.provider(),
                        request.baseUrl(), request.rootReference(), request.secretRef(),
                        request.groupMapping(), request.defaultVisibility(), request.enabled()))));
    }

    @PostMapping("/{connectionId}/sync")
    public ResponseEntity<ApiResponse<?>> synchronize(@PathVariable long connectionId) {
        return ResponseEntity.ok(ApiResponse.ok(service.synchronize(connectionId)));
    }

    @GetMapping("/issues")
    public ResponseEntity<ApiResponse<?>> issues() {
        return ResponseEntity.ok(ApiResponse.ok(service.issues()));
    }

    public record ConnectionRequest(
            @NotBlank @Size(max = 100) String connectionKey,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull KnowledgeSourceProvider provider,
            @Size(max = 500) String baseUrl,
            @Size(max = 500) String rootReference,
            @Size(max = 200) String secretRef,
            Map<String, KnowledgeVisibilityScope> groupMapping,
            @NotNull KnowledgeVisibilityScope defaultVisibility,
            boolean enabled) { }
}
