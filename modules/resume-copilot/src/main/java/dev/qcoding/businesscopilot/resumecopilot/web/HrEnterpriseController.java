package dev.qcoding.businesscopilot.resumecopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.resumecopilot.enterprise.HrEnterpriseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.time.Instant;
import java.util.List;

/** HR 企业授权、题库、面试协作、ATS 只读和入职清单 API。 */
@RestController
@RequestMapping("/api/resume-copilot/enterprise")
public class HrEnterpriseController {

    private final HrEnterpriseService service;

    public HrEnterpriseController(HrEnterpriseService service) {
        this.service = service;
    }

    @PostMapping("/consents")
    public ResponseEntity<ApiResponse<?>> saveConsent(@Valid @RequestBody ConsentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveConsent(
                new HrEnterpriseService.ConsentCommand(
                        request.consentReference(), request.candidateReference(), request.purpose(),
                        request.grantedAt(), request.expiresAt()))));
    }

    @PostMapping("/consents/{reference}/revoke")
    public ResponseEntity<ApiResponse<?>> revokeConsent(@PathVariable String reference) {
        return ResponseEntity.ok(ApiResponse.ok(service.revokeConsent(reference)));
    }

    @PostMapping("/authorized-assessments")
    public ResponseEntity<ApiResponse<?>> authorizedAssessment(
            @Valid @RequestBody AuthorizedAssessmentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.assessAuthorized(
                request.jobId(), request.candidateReference(),
                request.consentReference(), request.resumeText())));
    }

    @GetMapping("/question-bank")
    public ResponseEntity<ApiResponse<?>> questions() {
        return ResponseEntity.ok(ApiResponse.ok(service.questions()));
    }

    @PostMapping("/question-bank")
    public ResponseEntity<ApiResponse<?>> saveQuestion(@Valid @RequestBody QuestionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveQuestion(
                new HrEnterpriseService.QuestionCommand(
                        request.questionKey(), request.category(), request.questionText(),
                        request.evidenceGuidance(), request.prohibitedTopics()))));
    }

    @PostMapping("/question-bank/{id}/approve")
    public ResponseEntity<ApiResponse<?>> approveQuestion(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.approveQuestion(id)));
    }

    @PostMapping("/interview-sessions")
    public ResponseEntity<ApiResponse<?>> openInterview(@Valid @RequestBody SessionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.openSession(request.assessmentId())));
    }

    @PostMapping("/interview-sessions/{sessionId}/opinions")
    public ResponseEntity<ApiResponse<?>> saveOpinion(
            @PathVariable long sessionId, @Valid @RequestBody OpinionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveOpinion(
                sessionId, new HrEnterpriseService.OpinionCommand(
                        request.evidence(), request.gaps(), request.opinion()))));
    }

    @GetMapping("/interview-sessions/{sessionId}/summary")
    public ResponseEntity<ApiResponse<?>> interviewSummary(@PathVariable long sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(service.interviewSummary(sessionId)));
    }

    @PostMapping("/ats-connections")
    public ResponseEntity<ApiResponse<?>> saveAts(
            @Valid @RequestBody AtsConnectionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveAtsConnection(
                new HrEnterpriseService.AtsConnectionCommand(
                        request.connectionKey(), request.displayName(), request.provider(),
                        request.baseUrl(), request.secretRef(), request.enabled()))));
    }

    @PostMapping("/ats-connections/{connectionId}/import")
    public ResponseEntity<ApiResponse<?>> importAts(
            @PathVariable long connectionId,
            @RequestParam String consentReference,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.importAts(connectionId, consentReference, limit)));
    }

    @GetMapping("/onboarding-checklists")
    public ResponseEntity<ApiResponse<?>> checklists() {
        return ResponseEntity.ok(ApiResponse.ok(service.checklists()));
    }

    @PostMapping("/onboarding-checklists")
    public ResponseEntity<ApiResponse<?>> saveChecklist(
            @Valid @RequestBody ChecklistRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveChecklist(
                new HrEnterpriseService.ChecklistCommand(
                        request.checklistKey(), request.title(), request.roleScope(),
                        request.items(), request.knowledgeReferences()))));
    }

    @PostMapping("/onboarding-checklists/{id}/approve")
    public ResponseEntity<ApiResponse<?>> approveChecklist(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.approveChecklist(id)));
    }

    public record ConsentRequest(
            @NotBlank @Size(max = 200) String consentReference,
            @NotBlank @Size(max = 200) String candidateReference,
            @NotBlank @Size(max = 300) String purpose,
            @NotNull Instant grantedAt,
            @NotNull Instant expiresAt) { }
    public record AuthorizedAssessmentRequest(
            @NotNull Long jobId,
            @NotBlank @Size(max = 200) String candidateReference,
            @NotBlank @Size(max = 200) String consentReference,
            @NotBlank @Size(max = 20000) String resumeText) { }
    public record QuestionRequest(
            @NotBlank @Size(max = 100) String questionKey,
            @NotBlank @Size(max = 80) String category,
            @NotBlank @Size(max = 1000) String questionText,
            @NotBlank @Size(max = 1500) String evidenceGuidance,
            List<String> prohibitedTopics) { }
    public record SessionRequest(@NotNull Long assessmentId) { }
    public record OpinionRequest(
            @NotEmpty List<String> evidence,
            List<String> gaps,
            @NotBlank @Size(max = 4000) String opinion) { }
    public record AtsConnectionRequest(
            @NotBlank @Size(max = 100) String connectionKey,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull HrEnterpriseService.AtsProvider provider,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 200) String secretRef,
            boolean enabled) { }
    public record ChecklistRequest(
            @NotBlank @Size(max = 100) String checklistKey,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 100) String roleScope,
            @NotEmpty List<HrEnterpriseService.ChecklistItem> items,
            List<String> knowledgeReferences) { }
}
