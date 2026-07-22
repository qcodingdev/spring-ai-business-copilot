package dev.qcoding.businesscopilot.resumecopilot.assessment;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
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
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

public class ResumeAssessmentService {
    private static final String PROMPT = "resume-copilot/resume-assessment.st";
    private static final String POLICY_VERSION = "resume-evidence-guardrails-v2.1";
    private final ResumePrivacySanitizer sanitizer;
    private final ResumeEvidenceService evidenceService;
    private final JobCriteriaService criteriaService;
    private final ResumeAssessmentGuardrail guardrail;
    private final ResumeRepository repository;
    private final AiChatService ai;
    private final PromptTemplateService prompts;
    private final ResumeCopilotProperties properties;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final ConfirmationTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final DocumentTextExtractor documentTextExtractor;

    public ResumeAssessmentService(ResumePrivacySanitizer sanitizer, ResumeEvidenceService evidenceService,
                                   JobCriteriaService criteriaService, ResumeAssessmentGuardrail guardrail,
                                   ResumeRepository repository, AiChatService ai, PromptTemplateService prompts,
                                   ResumeCopilotProperties properties,
                                   CurrentActorProvider actorProvider,
                                   ObjectAccessPolicy accessPolicy,
                                   ConfirmationTokenService tokenService,
                                   ObjectMapper objectMapper,
                                   DocumentTextExtractor documentTextExtractor) {
        this.sanitizer = sanitizer;
        this.evidenceService = evidenceService;
        this.criteriaService = criteriaService;
        this.guardrail = guardrail;
        this.repository = repository;
        this.ai = ai;
        this.prompts = prompts;
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.documentTextExtractor = documentTextExtractor;
    }

    public AssessmentResponse assess(long jobId, String resumeText) {
        return assess(jobId, resumeText, "text-input.txt", "text/plain");
    }

    public AssessmentResponse assessFile(long jobId, String fileName, String contentType, byte[] content) {
        return assess(jobId, documentTextExtractor.extract(fileName, contentType, content).text(),
                fileName, contentType);
    }

    private AssessmentResponse assess(long jobId, String resumeText, String fileName, String contentType) {
        ResumeJobEntity job = repository.findJob(jobId);
        CurrentActor actor = actorProvider.currentActor();
        if (job == null || !accessPolicy.allowed(
                actor, ObjectAction.EXECUTE, job.getOwnerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!ResumeModels.Status.CRITERIA_CONFIRMED.name().equals(job.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String sanitizedResume = sanitizer.sanitizeResume(resumeText);
        List<ResumeModels.ResumeEvidence> evidence = evidenceService.extract(sanitizedResume);
        if (evidence.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未找到可用的简历证据。");
        Instant submissionExpiresAt = Instant.now().plus(properties.submissionRetention());
        long submissionId = repository.persistSubmission(
                jobId, sanitizedResume, evidence, fileName, contentType, submissionExpiresAt);
        repository.audit("RESUME_SANITIZED", jobId, submissionId, null, 0, evidence.size(), null,
                "SANITIZED", null);
        List<ResumeModels.JobCriterion> criteria = criteriaService.criteria(job);
        String modelName = ai.modelName();
        long startMs = System.currentTimeMillis();
        RenderedPrompt prompt = prompts.renderWithMetadata(PROMPT, "v2.1", Map.of(
                "jobTitle", job.getTitle(),
                "criteria", formatCriteria(criteria), "evidence", formatEvidence(evidence)));
        AiInvocationMetadata aiMetadata = null;
        try {
            AiInvocationResult<ResumeModels.AssessmentContent> invocation =
                    ai.generateJsonWithMetadata(
                            "resume.assessment", prompt.content(), ResumeModels.AssessmentContent.class);
            aiMetadata = invocation.metadata();
            if (aiMetadata != null && aiMetadata.modelName() != null) {
                modelName = aiMetadata.modelName();
            }
            ResumeModels.AssessmentContent output = invocation.content();
            var validation = guardrail.validate(output, criteria, evidence);
            ResumeModels.Status status = validation.valid() ? ResumeModels.Status.DRAFTED : ResumeModels.Status.NEEDS_REVIEW;
            ResumeModels.AssessmentContent safeContent = validation.valid() ? guardrail.normalizeNotFound(output) : null;
            Instant now = Instant.now();
            ConfirmationTokenService.IssuedToken token = tokenService.issue();
            ResumeAssessmentEntity entity = new ResumeAssessmentEntity();
            entity.setJobId(jobId);
            entity.setSubmissionId(submissionId);
            entity.setContentJson(safeContent == null ? "{}" : write(safeContent));
            entity.setStatus(status.name());
            entity.setReviewReasons(validation.valid() ? null : String.join("\n", validation.reasons()));
            entity.setReviewTokenDigest(token.digest());
            entity.setOwnerActorId(actor.actorId());
            entity.setReviewQueue(true);
            entity.setCriteriaVersion(job.getCriteriaVersion());
            entity.setOriginalContentJson(safeContent == null ? "{}" : write(safeContent));
            entity.setExpiresAt(now.plus(properties.reviewTokenTtl()));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            repository.insertAssessment(entity);
            repository.audit(
                    status == ResumeModels.Status.DRAFTED ? "ASSESSMENT_DRAFTED" : "NEEDS_REVIEW",
                    jobId, submissionId, entity.getId(), criteria.size(), evidence.size(),
                    modelName, status.name(), null, prompt.metadata(), aiMetadata,
                    POLICY_VERSION, validation.valid() ? null : "ASSESSMENT_EVIDENCE_VALIDATION",
                    System.currentTimeMillis() - startMs, actor.actorId(), null);
            return new AssessmentResponse(entity.getId(), jobId, submissionId, job.getCriteriaVersion(),
                    status, safeContent,
                    validation.reasons(), token.rawToken(), entity.getExpiresAt().toString(), evidence);
        } catch (RuntimeException ex) {
            repository.audit("FAILED", jobId, submissionId, null,
                    criteria.size(), evidence.size(), modelName,
                    ResumeModels.Status.FAILED.name(), null, prompt.metadata(), aiMetadata,
                    POLICY_VERSION, ErrorCode.AI_MODEL_ERROR.code(),
                    System.currentTimeMillis() - startMs, actor.actorId(), null);
            throw ex;
        }
    }

    @Transactional
    public StatusResponse review(long assessmentId, String token) {
        return review(assessmentId, token, null, null);
    }

    @Transactional
    public StatusResponse review(long assessmentId, String token,
                                 ResumeModels.AssessmentContent correctedContent,
                                 String reviewerFeedback) {
        ResumeAssessmentEntity assessment = requireAssessment(assessmentId);
        CurrentActor actor = actorProvider.currentActor();
        requireAccess(assessment, actor, ObjectAction.REVIEW);
        validateTokenAndExpiry(assessment, token);
        ResumeModels.Status current = ResumeModels.Status.valueOf(assessment.getStatus());
        if (current != ResumeModels.Status.DRAFTED && current != ResumeModels.Status.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        ResumeModels.AssessmentContent reviewedContent;
        ResumeReviewOutcome outcome;
        if (correctedContent == null) {
            if (current == ResumeModels.Status.NEEDS_REVIEW) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "NEEDS_REVIEW 状态必须提交经过修订且有证据依据的评估。");
            }
            reviewedContent = read(assessment.getContentJson());
            outcome = ResumeReviewOutcome.ACCEPTED;
        } else {
            reviewedContent = sanitizeReviewerContent(correctedContent);
            outcome = ResumeReviewOutcome.EDITED_ACCEPTED;
        }
        ResumeJobEntity job = repository.findJob(assessment.getJobId());
        List<ResumeModels.JobCriterion> criteria = criteriaService.criteria(job);
        List<ResumeModels.ResumeEvidence> evidence = repository.findEvidence(assessment.getSubmissionId());
        var validation = guardrail.validate(reviewedContent, criteria, evidence);
        if (!validation.valid()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, String.join("; ", validation.reasons()));
        }
        reviewedContent = guardrail.normalizeNotFound(reviewedContent);
        String safeFeedback = sanitizer.sanitizeReviewerFeedback(reviewerFeedback);
        if (!repository.reviewAssessment(
                assessmentId, current, write(reviewedContent), safeFeedback,
                outcome.name(), actor.actorId(), Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        repository.auditRequired("REVIEWED", assessment.getJobId(), assessment.getSubmissionId(),
                assessmentId, 0, 0, null, ResumeModels.Status.REVIEWED.name(), null,
                assessment.getOwnerActorId(), actor.actorId());
        return new StatusResponse(assessmentId, ResumeModels.Status.REVIEWED);
    }

    public ReviewView reviewView(long assessmentId) {
        ResumeAssessmentEntity assessment = requireAssessment(assessmentId);
        CurrentActor actor = actorProvider.currentActor();
        requireAccess(assessment, actor, ObjectAction.REVIEW);
        return new ReviewView(
                assessment.getId(), assessment.getJobId(), assessment.getSubmissionId(),
                assessment.getCriteriaVersion(), ResumeModels.Status.valueOf(assessment.getStatus()),
                read(assessment.getContentJson()), read(assessment.getOriginalContentJson()),
                assessment.getReviewReasons(), assessment.getReviewerFeedback(),
                assessment.getDecisionOutcome(), repository.findEvidence(assessment.getSubmissionId()),
                assessment.getExpiresAt());
    }

    public boolean deleteSubmission(long submissionId) {
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return repository.deleteSubmission(
                submissionId, actor.actorId(), actor.hasRole(BusinessRole.ADMIN));
    }

    @Transactional
    public StatusResponse cancel(long assessmentId, String token) {
        ResumeAssessmentEntity assessment = requireAssessment(assessmentId);
        CurrentActor actor = actorProvider.currentActor();
        boolean allowed = accessPolicy.allowed(actor, ObjectAction.CANCEL,
                assessment.getOwnerActorId(), assessment.getReviewerActorId(), assessment.isReviewQueue())
                || accessPolicy.allowed(actor, ObjectAction.REVIEW,
                assessment.getOwnerActorId(), assessment.getReviewerActorId(), assessment.isReviewQueue());
        if (!allowed) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        validateTokenAndExpiry(assessment, token);
        ResumeModels.Status current = ResumeModels.Status.valueOf(assessment.getStatus());
        if ((current != ResumeModels.Status.DRAFTED && current != ResumeModels.Status.NEEDS_REVIEW)
                || !repository.transitionAssessment(
                assessmentId, current, ResumeModels.Status.CANCELED,
                actor.actorId(), Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        repository.auditRequired("CANCELED", assessment.getJobId(), assessment.getSubmissionId(),
                assessmentId, 0, 0, null, ResumeModels.Status.CANCELED.name(), null,
                assessment.getOwnerActorId(), actor.actorId());
        return new StatusResponse(assessmentId, ResumeModels.Status.CANCELED);
    }

    private ResumeAssessmentEntity requireAssessment(long id) {
        ResumeAssessmentEntity entity = repository.findAssessment(id);
        if (entity == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return entity;
    }

    private void requireAccess(ResumeAssessmentEntity assessment, CurrentActor actor, ObjectAction action) {
        if (!accessPolicy.allowed(actor, action, assessment.getOwnerActorId(),
                assessment.getReviewerActorId(), assessment.isReviewQueue())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private void validateTokenAndExpiry(ResumeAssessmentEntity assessment, String rawToken) {
        if (!tokenService.matches(rawToken, assessment.getReviewTokenDigest())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (assessment.getExpiresAt() == null || !assessment.getExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
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
        catch (JacksonException ex) { throw new IllegalStateException("简历评估序列化失败", ex); }
    }

    private ResumeModels.AssessmentContent read(String contentJson) {
        if (contentJson == null || contentJson.isBlank() || "{}".equals(contentJson.trim())) {
            return null;
        }
        try {
            return objectMapper.readValue(contentJson, ResumeModels.AssessmentContent.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("简历评估反序列化失败", ex);
        }
    }

    private ResumeModels.AssessmentContent sanitizeReviewerContent(ResumeModels.AssessmentContent content) {
        return new ResumeModels.AssessmentContent(
                sanitizer.sanitizeReviewerFeedback(content.anonymousSummary()),
                content.criterionAssessments().stream()
                        .map(item -> new ResumeModels.CriterionAssessment(
                                item.criterionId(), item.status(),
                                sanitizer.sanitizeReviewerFeedback(item.explanation()),
                                item.evidenceIds()))
                        .toList(),
                content.evidenceGaps().stream().map(sanitizer::sanitizeReviewerFeedback).toList(),
                content.interviewQuestions().stream()
                        .map(item -> new ResumeModels.InterviewQuestion(
                                item.criterionId(),
                                sanitizer.sanitizeReviewerFeedback(item.question()),
                                item.evidenceIds()))
                        .toList(),
                content.limitations().stream().map(sanitizer::sanitizeReviewerFeedback).toList());
    }

    public record AssessmentResponse(Long assessmentId, Long jobId, Long submissionId, int criteriaVersion,
                                     ResumeModels.Status status,
                                     ResumeModels.AssessmentContent content, List<String> reviewReasons,
                                     String reviewToken, String expiresAt,
                                     List<ResumeModels.ResumeEvidence> evidence) { }
    public record StatusResponse(Long assessmentId, ResumeModels.Status status) { }
    public record ReviewView(Long assessmentId, Long jobId, Long submissionId, int criteriaVersion,
                             ResumeModels.Status status, ResumeModels.AssessmentContent content,
                             ResumeModels.AssessmentContent originalContent, String reviewReasons,
                             String reviewerFeedback, String decisionOutcome,
                             List<ResumeModels.ResumeEvidence> evidence, Instant expiresAt) { }
}
