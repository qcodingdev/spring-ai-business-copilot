package dev.qcoding.businesscopilot.resumecopilot.assessment;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import dev.qcoding.businesscopilot.resumecopilot.evidence.ResumeEvidenceService;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaService;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeAssessmentEntity;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeJobEntity;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeRepository;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ResumeAssessmentService {
    private static final String PROMPT = "resume-copilot/resume-assessment.st";
    private final ResumePrivacySanitizer sanitizer;
    private final ResumeEvidenceService evidenceService;
    private final JobCriteriaService criteriaService;
    private final ResumeAssessmentGuardrail guardrail;
    private final ResumeRepository repository;
    private final AiChatService ai;
    private final PromptTemplateService prompts;
    private final ResumeCopilotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResumeAssessmentService(ResumePrivacySanitizer sanitizer, ResumeEvidenceService evidenceService,
                                   JobCriteriaService criteriaService, ResumeAssessmentGuardrail guardrail,
                                   ResumeRepository repository, AiChatService ai, PromptTemplateService prompts,
                                   ResumeCopilotProperties properties) {
        this.sanitizer = sanitizer;
        this.evidenceService = evidenceService;
        this.criteriaService = criteriaService;
        this.guardrail = guardrail;
        this.repository = repository;
        this.ai = ai;
        this.prompts = prompts;
        this.properties = properties;
    }

    public AssessmentResponse assess(long jobId, String resumeText) {
        ResumeJobEntity job = repository.findJob(jobId);
        if (job == null || !ResumeModels.Status.CRITERIA_CONFIRMED.name().equals(job.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Job criteria must be confirmed before resume assessment.");
        }
        String sanitizedResume = sanitizer.sanitizeResume(resumeText);
        List<ResumeModels.ResumeEvidence> evidence = evidenceService.extract(sanitizedResume);
        if (evidence.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "No usable resume evidence was found.");
        long submissionId = repository.persistSubmission(jobId, sanitizedResume, evidence);
        repository.audit("RESUME_SANITIZED", jobId, submissionId, null, 0, evidence.size(), null,
                "SANITIZED", null);
        List<ResumeModels.JobCriterion> criteria = criteriaService.criteria(job);
        String modelName = ai.modelName();
        try {
            String prompt = prompts.render(PROMPT, Map.of("jobTitle", job.getTitle(),
                    "criteria", formatCriteria(criteria), "evidence", formatEvidence(evidence)));
            ResumeModels.AssessmentContent output = ai.generateJson(prompt, ResumeModels.AssessmentContent.class);
            var validation = guardrail.validate(output, criteria, evidence);
            ResumeModels.Status status = validation.valid() ? ResumeModels.Status.DRAFTED : ResumeModels.Status.NEEDS_REVIEW;
            ResumeModels.AssessmentContent safeContent = validation.valid() ? guardrail.normalizeNotFound(output) : null;
            Instant now = Instant.now();
            ResumeAssessmentEntity entity = new ResumeAssessmentEntity();
            entity.setJobId(jobId);
            entity.setSubmissionId(submissionId);
            entity.setContentJson(safeContent == null ? "{}" : write(safeContent));
            entity.setStatus(status.name());
            entity.setReviewReasons(validation.valid() ? null : String.join("\n", validation.reasons()));
            entity.setReviewToken(UUID.randomUUID().toString());
            entity.setExpiresAt(now.plus(properties.reviewTokenTtl()));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            repository.insertAssessment(entity);
            repository.audit(status == ResumeModels.Status.DRAFTED ? "ASSESSMENT_DRAFTED" : "NEEDS_REVIEW",
                    jobId, submissionId, entity.getId(), criteria.size(), evidence.size(), modelName, status.name(),
                    validation.valid() ? null : "Assessment failed deterministic evidence or hiring guardrails.");
            return new AssessmentResponse(entity.getId(), jobId, submissionId, status, safeContent,
                    validation.reasons(), entity.getReviewToken(), entity.getExpiresAt().toString(), evidence);
        } catch (RuntimeException ex) {
            repository.audit("FAILED", jobId, submissionId, null, criteria.size(), evidence.size(), modelName,
                    ResumeModels.Status.FAILED.name(), "Resume assessment generation failed.");
            throw ex;
        }
    }

    public StatusResponse review(long assessmentId, String token) {
        ResumeAssessmentEntity assessment = requireAssessment(assessmentId);
        if (!ResumeModels.Status.DRAFTED.name().equals(assessment.getStatus())
                || !repository.transitionAssessment(assessmentId, token, ResumeModels.Status.DRAFTED, ResumeModels.Status.REVIEWED)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Only an unexpired DRAFTED assessment can be marked REVIEWED.");
        }
        repository.audit("REVIEWED", assessment.getJobId(), assessment.getSubmissionId(), assessmentId, 0, 0,
                null, ResumeModels.Status.REVIEWED.name(), null);
        return new StatusResponse(assessmentId, ResumeModels.Status.REVIEWED);
    }

    public StatusResponse cancel(long assessmentId, String token) {
        ResumeAssessmentEntity assessment = requireAssessment(assessmentId);
        ResumeModels.Status current = ResumeModels.Status.valueOf(assessment.getStatus());
        if ((current != ResumeModels.Status.DRAFTED && current != ResumeModels.Status.NEEDS_REVIEW)
                || !repository.transitionAssessment(assessmentId, token, current, ResumeModels.Status.CANCELED)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Only DRAFTED or NEEDS_REVIEW assessments can be canceled.");
        }
        repository.audit("CANCELED", assessment.getJobId(), assessment.getSubmissionId(), assessmentId, 0, 0,
                null, ResumeModels.Status.CANCELED.name(), null);
        return new StatusResponse(assessmentId, ResumeModels.Status.CANCELED);
    }

    private ResumeAssessmentEntity requireAssessment(long id) {
        ResumeAssessmentEntity entity = repository.findAssessment(id);
        if (entity == null) throw new BusinessException(ErrorCode.NOT_FOUND, "Resume assessment not found.");
        return entity;
    }

    private String formatCriteria(List<ResumeModels.JobCriterion> criteria) {
        return criteria.stream().map(item -> item.criterionId() + " | " + item.requirementType() + " | "
                + item.description()).collect(Collectors.joining("\n"));
    }

    private String formatEvidence(List<ResumeModels.ResumeEvidence> evidence) {
        return evidence.stream().map(item -> item.evidenceId() + " | " + item.section() + " | "
                + item.sanitizedText()).collect(Collectors.joining("\n"));
    }

    private String write(ResumeModels.AssessmentContent content) {
        try { return objectMapper.writeValueAsString(content); }
        catch (JacksonException ex) { throw new IllegalStateException("Unable to serialize resume assessment", ex); }
    }

    public record AssessmentResponse(Long assessmentId, Long jobId, Long submissionId, ResumeModels.Status status,
                                     ResumeModels.AssessmentContent content, List<String> reviewReasons,
                                     String reviewToken, String expiresAt,
                                     List<ResumeModels.ResumeEvidence> evidence) { }
    public record StatusResponse(Long assessmentId, ResumeModels.Status status) { }
}
