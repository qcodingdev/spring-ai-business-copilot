package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeJobEntity;
import dev.qcoding.businesscopilot.resumecopilot.persistence.ResumeRepository;
import dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

public class JobCriteriaService {
    private static final String PROMPT = "resume-copilot/job-criteria-extraction.st";
    private final ResumePrivacySanitizer sanitizer;
    private final AiChatService ai;
    private final PromptTemplateService prompts;
    private final JobCriteriaGuardrail guardrail;
    private final ResumeRepository repository;
    private final ResumeCopilotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobCriteriaService(ResumePrivacySanitizer sanitizer, AiChatService ai, PromptTemplateService prompts,
                              JobCriteriaGuardrail guardrail, ResumeRepository repository,
                              ResumeCopilotProperties properties) {
        this.sanitizer = sanitizer;
        this.ai = ai;
        this.prompts = prompts;
        this.guardrail = guardrail;
        this.repository = repository;
        this.properties = properties;
    }

    public CriteriaResponse extract(String title, String jobDescription) {
        if (title == null || title.isBlank() || title.length() > 300) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Job title is required and must not exceed 300 characters.");
        }
        String sanitizedJd = sanitizer.sanitizeJobDescription(jobDescription);
        String modelName = ai.modelName();
        try {
            String prompt = prompts.render(PROMPT, Map.of("jobTitle", title.trim(), "jobDescription", sanitizedJd));
            var output = ai.generateJson(prompt, ResumeModels.LlmJobCriteriaOutput.class);
            List<ResumeModels.JobCriterion> criteria = assignServerIds(output == null ? List.of() : output.criteria());
            var validation = guardrail.validate(criteria, sanitizedJd);
            if (!validation.valid()) {
                repository.audit("FAILED", null, null, null, criteria.size(), 0, modelName,
                        ResumeModels.Status.FAILED.name(), "Job criteria failed deterministic validation.");
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, String.join("; ", validation.reasons()));
            }
            Instant now = Instant.now();
            ResumeJobEntity job = new ResumeJobEntity();
            job.setTitle(title.trim());
            job.setSanitizedJd(sanitizedJd);
            job.setCriteriaJson(write(criteria));
            job.setStatus(ResumeModels.Status.CRITERIA_DRAFTED.name());
            job.setCriteriaToken(UUID.randomUUID().toString());
            job.setExpiresAt(now.plus(properties.reviewTokenTtl()));
            job.setCreatedAt(now);
            job.setUpdatedAt(now);
            repository.insertJob(job);
            repository.audit("CRITERIA_EXTRACTED", job.getId(), null, null, criteria.size(), 0, modelName,
                    job.getStatus(), null);
            return new CriteriaResponse(job.getId(), job.getTitle(), ResumeModels.Status.CRITERIA_DRAFTED,
                    criteria, job.getCriteriaToken(), job.getExpiresAt().toString());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            repository.audit("FAILED", null, null, null, 0, 0, modelName, ResumeModels.Status.FAILED.name(),
                    "Job criteria extraction failed.");
            throw ex;
        }
    }

    public StatusResponse confirm(long jobId, String token) {
        if (token == null || token.isBlank() || !repository.confirmCriteria(jobId, token, Instant.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Job criteria token is invalid, expired, or already used.");
        }
        ResumeJobEntity job = repository.findJob(jobId);
        repository.audit("CRITERIA_CONFIRMED", jobId, null, null, read(job.getCriteriaJson()).size(), 0,
                null, ResumeModels.Status.CRITERIA_CONFIRMED.name(), null);
        return new StatusResponse(jobId, ResumeModels.Status.CRITERIA_CONFIRMED);
    }

    public List<ResumeModels.JobCriterion> criteria(ResumeJobEntity job) { return read(job.getCriteriaJson()); }

    private List<ResumeModels.JobCriterion> assignServerIds(List<ResumeModels.JobCriterion> criteria) {
        return IntStream.range(0, criteria.size()).mapToObj(index -> {
            var item = criteria.get(index);
            return new ResumeModels.JobCriterion("criterion-" + (index + 1), item.category(), item.requirementType(),
                    item.description(), item.normalizedKeywords(), item.sourceText());
        }).toList();
    }

    private String write(List<ResumeModels.JobCriterion> criteria) {
        try { return objectMapper.writeValueAsString(criteria); }
        catch (JacksonException ex) { throw new IllegalStateException("Unable to serialize job criteria", ex); }
    }

    private List<ResumeModels.JobCriterion> read(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class,
                    ResumeModels.JobCriterion.class));
        } catch (JacksonException ex) { throw new IllegalStateException("Unable to deserialize job criteria", ex); }
    }

    public record CriteriaResponse(Long jobId, String title, ResumeModels.Status status,
                                   List<ResumeModels.JobCriterion> criteria, String confirmationToken, String expiresAt) { }
    public record StatusResponse(Long jobId, ResumeModels.Status status) { }
}
