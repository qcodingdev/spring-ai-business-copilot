package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Prompt draft, review, evaluation-gated publish and rollback API. */
@RestController
@RequestMapping("/api/governance/prompts")
public class PromptGovernanceController {

    private final PromptGovernanceService service;

    public PromptGovernanceController(PromptGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> definitions() {
        return ResponseEntity.ok(ApiResponse.ok(service.definitions()));
    }

    @GetMapping("/{definitionId}/audit")
    public ResponseEntity<ApiResponse<?>> audit(@PathVariable long definitionId) {
        return ResponseEntity.ok(ApiResponse.ok(service.audit(definitionId)));
    }

    @PostMapping("/{promptKey}/versions")
    public ResponseEntity<ApiResponse<?>> createVersion(
            @PathVariable String promptKey, @Valid @RequestBody VersionCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.createVersion(
                promptKey, request.content(), request.changeNote())));
    }

    @PostMapping("/definitions/{definitionId}/versions")
    public ResponseEntity<ApiResponse<?>> createVersionByDefinition(
            @PathVariable long definitionId, @Valid @RequestBody VersionCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.createVersion(
                definitionId, request.content(), request.changeNote())));
    }

    @PutMapping("/versions/{versionId}")
    public ResponseEntity<ApiResponse<?>> updateVersion(
            @PathVariable long versionId, @Valid @RequestBody UpdateVersionCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateDraft(versionId,
                request.expectedContentHash(), request.content(), request.changeNote())));
    }

    @PostMapping("/versions/{versionId}/submit")
    public ResponseEntity<ApiResponse<?>> submit(@PathVariable long versionId) {
        return ResponseEntity.ok(ApiResponse.ok(service.submit(versionId)));
    }

    @PostMapping("/versions/{versionId}/review")
    public ResponseEntity<ApiResponse<?>> review(
            @PathVariable long versionId, @Valid @RequestBody ReviewCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.review(
                versionId, request.approve(), request.note())));
    }

    @PostMapping("/versions/{versionId}/publish")
    public ResponseEntity<ApiResponse<?>> publish(
            @PathVariable long versionId, @Valid @RequestBody PublishCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.publish(
                versionId, request.rolloutPercent(), request.evaluationRunId())));
    }

    @PostMapping("/{definitionId}/rollback")
    public ResponseEntity<ApiResponse<?>> rollback(
            @PathVariable long definitionId, @Valid @RequestBody NoteCommand request) {
        return ResponseEntity.ok(ApiResponse.ok(service.rollback(definitionId, request.note())));
    }

    public record VersionCommand(
            @NotBlank @Size(max = 50_000) String content,
            @NotBlank @Size(max = 1000) String changeNote) {
    }

    public record UpdateVersionCommand(
            @NotBlank @Size(max = 64) String expectedContentHash,
            @NotBlank @Size(max = 50_000) String content,
            @NotBlank @Size(max = 1000) String changeNote) {
    }

    public record ReviewCommand(boolean approve, @NotBlank @Size(max = 1000) String note) {
    }

    public record PublishCommand(
            @Min(1) @Max(100) int rolloutPercent,
            @NotNull UUID evaluationRunId) {
    }

    public record NoteCommand(@NotBlank @Size(max = 1000) String note) {
    }
}
