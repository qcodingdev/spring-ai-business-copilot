package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
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
import org.springframework.transaction.annotation.Transactional;

public class JobCriteriaService {
    private static final String PROMPT = "resume-copilot/job-criteria-extraction.st";
    private static final String POLICY_VERSION = "resume-job-criteria-v2.1";
    private final ResumePrivacySanitizer sanitizer;
    private final AiChatService ai;
    private final PromptTemplateService prompts;
    private final JobCriteriaGuardrail guardrail;
    private final ResumeRepository repository;
    private final ResumeCopilotProperties properties;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final ConfirmationTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final DocumentTextExtractor documentTextExtractor;

    public JobCriteriaService(ResumePrivacySanitizer sanitizer, AiChatService ai, PromptTemplateService prompts,
                              JobCriteriaGuardrail guardrail, ResumeRepository repository,
                              ResumeCopilotProperties properties,
                              CurrentActorProvider actorProvider,
                              ObjectAccessPolicy accessPolicy,
                              ConfirmationTokenService tokenService,
                              ObjectMapper objectMapper,
                              DocumentTextExtractor documentTextExtractor) {
        this.sanitizer = sanitizer;
        this.ai = ai;
        this.prompts = prompts;
        this.guardrail = guardrail;
        this.repository = repository;
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.documentTextExtractor = documentTextExtractor;
    }

    public CriteriaResponse extract(String title, String jobDescription) {
        return extract(title, jobDescription, null);
    }

    public CriteriaResponse extractFile(String title, String fileName, String contentType,
                                        byte[] content, UUID logicalJobId) {
        return extract(title,
                documentTextExtractor.extract(fileName, contentType, content).text(),
                logicalJobId);
    }

    public CriteriaResponse extract(String title, String jobDescription, UUID requestedLogicalJobId) {
        if (title == null || title.isBlank() || title.length() > 300) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "职位名称不能为空且不能超过 300 个字符。");
        }
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        UUID logicalJobId = requestedLogicalJobId == null ? UUID.randomUUID() : requestedLogicalJobId;
        ResumeJobEntity currentVersion = repository.findCurrentJob(logicalJobId);
        if (currentVersion != null && !accessPolicy.allowed(
                actor, ObjectAction.CREATE, currentVersion.getOwnerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String sanitizedJd = sanitizer.sanitizeJobDescription(jobDescription);
        String modelName = ai.modelName();
        long startMs = System.currentTimeMillis();
        RenderedPrompt prompt = prompts.renderWithMetadata(
                PROMPT, "v2.1", Map.of("jobTitle", title.trim(), "jobDescription", sanitizedJd));
        AiInvocationMetadata aiMetadata = null;
        try {
            AiInvocationResult<ResumeModels.LlmJobCriteriaOutput> invocation =
                    ai.generateJsonWithMetadata(prompt.content(), ResumeModels.LlmJobCriteriaOutput.class);
            aiMetadata = invocation.metadata();
            if (aiMetadata != null && aiMetadata.modelName() != null) {
                modelName = aiMetadata.modelName();
            }
            var output = invocation.content();
            List<ResumeModels.JobCriterion> criteria = assignServerIds(output == null ? List.of() : output.criteria());
            var validation = guardrail.validate(criteria, sanitizedJd);
            if (!validation.valid()) {
                repository.audit("FAILED", null, null, null, criteria.size(), 0, modelName,
                        ResumeModels.Status.FAILED.name(), null, prompt.metadata(), aiMetadata,
                        POLICY_VERSION, "JOB_CRITERIA_VALIDATION",
                        System.currentTimeMillis() - startMs,
                        actorProvider.currentActor().actorId(), null);
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, String.join("; ", validation.reasons()));
            }
            Instant now = Instant.now();
            ConfirmationTokenService.IssuedToken token = tokenService.issue();
            ResumeJobEntity job = new ResumeJobEntity();
            job.setTitle(title.trim());
            job.setSanitizedJd(sanitizedJd);
            job.setCriteriaJson(write(criteria));
            job.setStatus(ResumeModels.Status.CRITERIA_DRAFTED.name());
            job.setCriteriaTokenDigest(token.digest());
            job.setOwnerActorId(actor.actorId());
            job.setLogicalJobId(logicalJobId);
            job.setCriteriaVersion(repository.nextCriteriaVersion(logicalJobId));
            job.setCurrentVersion(true);
            job.setEffectiveFrom(now);
            job.setExpiresAt(now.plus(properties.reviewTokenTtl()));
            job.setCreatedAt(now);
            job.setUpdatedAt(now);
            repository.insertJobVersion(job);
            repository.audit("CRITERIA_EXTRACTED", job.getId(), null, null,
                    criteria.size(), 0, modelName, job.getStatus(), null,
                    prompt.metadata(), aiMetadata, POLICY_VERSION, null,
                    System.currentTimeMillis() - startMs, actor.actorId(), null);
            return new CriteriaResponse(job.getId(), job.getLogicalJobId(), job.getCriteriaVersion(),
                    job.getTitle(), ResumeModels.Status.CRITERIA_DRAFTED,
                    criteria, token.rawToken(), job.getExpiresAt().toString());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            repository.audit("FAILED", null, null, null, 0, 0, modelName,
                    ResumeModels.Status.FAILED.name(), null, prompt.metadata(), aiMetadata,
                    POLICY_VERSION, ErrorCode.AI_MODEL_ERROR.code(),
                    System.currentTimeMillis() - startMs,
                    actorProvider.currentActor().actorId(), null);
            throw ex;
        }
    }

    @Transactional
    public StatusResponse confirm(long jobId, String token) {
        ResumeJobEntity job = repository.findJob(jobId);
        CurrentActor actor = actorProvider.currentActor();
        if (job == null || !accessPolicy.allowed(
                actor, ObjectAction.CONFIRM, job.getOwnerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!ResumeModels.Status.CRITERIA_DRAFTED.name().equals(job.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if (!tokenService.matches(token, job.getCriteriaTokenDigest())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Instant now = Instant.now();
        if (job.getExpiresAt() == null || !job.getExpiresAt().isAfter(now)
                || !repository.confirmCriteria(jobId, ResumeModels.Status.CRITERIA_DRAFTED,
                actor.actorId(), now)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        repository.auditRequired("CRITERIA_CONFIRMED", jobId, null, null,
                read(job.getCriteriaJson()).size(), 0, null,
                ResumeModels.Status.CRITERIA_CONFIRMED.name(), null,
                job.getOwnerActorId(), actor.actorId());
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
        catch (JacksonException ex) { throw new IllegalStateException("职位标准序列化失败", ex); }
    }

    private List<ResumeModels.JobCriterion> read(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class,
                    ResumeModels.JobCriterion.class));
        } catch (JacksonException ex) { throw new IllegalStateException("职位标准反序列化失败", ex); }
    }

    public record CriteriaResponse(Long jobId, UUID logicalJobId, int criteriaVersion,
                                   String title, ResumeModels.Status status,
                                   List<ResumeModels.JobCriterion> criteria, String confirmationToken, String expiresAt) { }
    public record StatusResponse(Long jobId, ResumeModels.Status status) { }
}
