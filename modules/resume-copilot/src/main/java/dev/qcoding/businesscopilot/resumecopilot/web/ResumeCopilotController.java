package dev.qcoding.businesscopilot.resumecopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resume-copilot")
@ConditionalOnProperty(prefix = "business-copilot.resume-copilot", name = "enabled", havingValue = "true")
public class ResumeCopilotController {
    private final JobCriteriaService criteriaService;
    private final ResumeAssessmentService assessmentService;

    public ResumeCopilotController(JobCriteriaService criteriaService, ResumeAssessmentService assessmentService) {
        this.criteriaService = criteriaService;
        this.assessmentService = assessmentService;
    }

    @PostMapping("/jobs/criteria")
    public ResponseEntity<ApiResponse<JobCriteriaService.CriteriaResponse>> criteria(@Valid @RequestBody CriteriaRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(criteriaService.extract(request.title(), request.jobDescription())));
    }

    @PostMapping("/jobs/{jobId}/criteria/confirm")
    public ResponseEntity<ApiResponse<JobCriteriaService.StatusResponse>> confirmCriteria(
            @PathVariable long jobId, @Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(criteriaService.confirm(jobId, request.token())));
    }

    @PostMapping("/assessments")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.AssessmentResponse>> assess(
            @Valid @RequestBody AssessmentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assessmentService.assess(request.jobId(), request.resumeText())));
    }

    @PostMapping("/assessments/{assessmentId}/review")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.StatusResponse>> review(
            @PathVariable long assessmentId, @Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assessmentService.review(assessmentId, request.token())));
    }

    @PostMapping("/assessments/{assessmentId}/cancel")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.StatusResponse>> cancel(
            @PathVariable long assessmentId, @Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assessmentService.cancel(assessmentId, request.token())));
    }

    public record CriteriaRequest(@NotBlank String title, @NotBlank String jobDescription) { }
    public record AssessmentRequest(@NotNull Long jobId, @NotBlank String resumeText) { }
    public record TokenRequest(@NotBlank String token) { }
}
