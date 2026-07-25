package dev.qcoding.businesscopilot.resumecopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaService;
import dev.qcoding.businesscopilot.resumecopilot.job.JobDraftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/resume-copilot")
@ConditionalOnProperty(prefix = "business-copilot.resume-copilot", name = "enabled", havingValue = "true")
public class ResumeCopilotController {
    private final JobCriteriaService criteriaService;
    private final ResumeAssessmentService assessmentService;
    private final JobDraftService jobDraftService;

    public ResumeCopilotController(JobCriteriaService criteriaService,
                                   ResumeAssessmentService assessmentService,
                                   JobDraftService jobDraftService) {
        this.criteriaService = criteriaService;
        this.assessmentService = assessmentService;
        this.jobDraftService = jobDraftService;
    }

    @PostMapping("/jobs/draft")
    public ResponseEntity<ApiResponse<JobDraftService.JobDraftResponse>> draftJob(
            @Valid @RequestBody JobDraftRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(jobDraftService.generate(request.title(), request.requirements())));
    }

    @PostMapping("/jobs/criteria")
    public ResponseEntity<ApiResponse<JobCriteriaService.CriteriaResponse>> criteria(@Valid @RequestBody CriteriaRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(criteriaService.extract(
                request.title(), request.jobDescription(), request.logicalJobId())));
    }

    @PostMapping(path = "/jobs/criteria/file", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<JobCriteriaService.CriteriaResponse>> criteriaFile(
            @RequestPart("title") String title,
            @RequestPart("file") MultipartFile file,
            @RequestPart(name = "logicalJobId", required = false) UUID logicalJobId) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                criteriaService.extractFile(title, file.getOriginalFilename(), file.getContentType(),
                        file.getBytes(), logicalJobId)));
    }

    @PostMapping("/jobs/{jobId}/criteria/confirm")
    public ResponseEntity<ApiResponse<JobCriteriaService.StatusResponse>> confirmCriteria(
            @PathVariable long jobId, @Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(criteriaService.confirm(jobId, request.token())));
    }

    /** Current confirmed standards that can be selected for a separate candidate assessment flow. */
    @GetMapping("/jobs/confirmed")
    public ResponseEntity<ApiResponse<List<JobCriteriaService.ConfirmedJobSummary>>> confirmedJobs() {
        return ResponseEntity.ok(ApiResponse.ok(criteriaService.confirmedJobs()));
    }

    @PutMapping("/jobs/{jobId}/criteria")
    public ResponseEntity<ApiResponse<JobCriteriaService.CriteriaResponse>> updateCriteria(
            @PathVariable long jobId, @Valid @RequestBody CriteriaUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(criteriaService.updateDraft(jobId, request.criteria())));
    }

    @PostMapping("/assessments")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.AssessmentResponse>> assess(
            @Valid @RequestBody AssessmentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assessmentService.assess(request.jobId(), request.resumeText())));
    }

    @PostMapping(path = "/assessments/file", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.AssessmentResponse>> assessFile(
            @RequestPart("jobId") Long jobId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                assessmentService.assessFile(jobId, file.getOriginalFilename(),
                        file.getContentType(), file.getBytes())));
    }

    @GetMapping("/assessments/{assessmentId}/review")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.ReviewView>> reviewView(
            @PathVariable long assessmentId) {
        return ResponseEntity.ok(ApiResponse.ok(assessmentService.reviewView(assessmentId)));
    }

    @PostMapping("/assessments/{assessmentId}/review")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.StatusResponse>> review(
            @PathVariable long assessmentId, @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assessmentService.review(
                assessmentId, request.token(), request.correctedContent(), request.reviewerFeedback())));
    }

    @PostMapping("/assessments/{assessmentId}/cancel")
    public ResponseEntity<ApiResponse<ResumeAssessmentService.StatusResponse>> cancel(
            @PathVariable long assessmentId, @Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assessmentService.cancel(assessmentId, request.token())));
    }

    @DeleteMapping("/submissions/{submissionId}")
    public ResponseEntity<ApiResponse<Void>> deleteSubmission(@PathVariable long submissionId) {
        if (!assessmentService.deleteSubmission(submissionId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "脱敏简历提交记录已删除"));
    }

    public record CriteriaRequest(
                                  @NotBlank(message = "职位名称不能为空。") String title,
                                  @NotBlank(message = "职位描述不能为空。") String jobDescription,
                                  UUID logicalJobId) { }
    public record JobDraftRequest(
            @NotBlank(message = "职位名称不能为空。")
            @jakarta.validation.constraints.Size(max = 300, message = "职位名称不能超过 300 个字符。")
            String title,
            @NotBlank(message = "岗位需求不能为空。")
            @jakarta.validation.constraints.Size(max = 2000, message = "岗位需求不能超过 2000 个字符。")
            String requirements) { }
    public record CriteriaUpdateRequest(
            @NotNull(message = "岗位标准不能为空。")
            List<ResumeModels.JobCriterion> criteria) { }
    public record AssessmentRequest(
            @NotNull(message = "职位编号不能为空。") Long jobId,
            @NotBlank(message = "简历内容不能为空。") String resumeText) { }
    public record TokenRequest(@NotBlank(message = "确认凭证不能为空。") String token) { }
    public record ReviewRequest(@NotBlank(message = "复核凭证不能为空。") String token,
                                ResumeModels.AssessmentContent correctedContent,
                                String reviewerFeedback) { }
}
