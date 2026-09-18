package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiAttemptObserver;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService;
import dev.qcoding.businesscopilot.reportcopilot.enterprise.ReportPublicationClaim;
import dev.qcoding.businesscopilot.taskruntime.ContextManifest;
import dev.qcoding.businesscopilot.taskruntime.FailureCategory;
import dev.qcoding.businesscopilot.taskruntime.RuntimeFailureCategories;
import dev.qcoding.businesscopilot.taskruntime.TaskRun;
import dev.qcoding.businesscopilot.taskruntime.TaskRunBudget;
import dev.qcoding.businesscopilot.taskruntime.TaskRunService;
import dev.qcoding.businesscopilot.taskruntime.TaskStep;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Generates a review-only structured report candidate from prepared evidence. */
public class ReportGenerationService {

    private static final String PROMPT_LOCATION = "report-copilot/report-generation.st";
    private static final String POLICY_VERSION = "report-evidence-guardrails-v3.1";

    private final ReportRequestPreparationService preparationService;
    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final ReportPromptContextFactory promptContextFactory;
    private final ReportGenerationOutputValidator outputValidator;
    private final ReportOutputSanitizer outputSanitizer;
    private final ReportDraftPersistenceService draftPersistenceService;
    private final TaskRunService taskRunService;

    public ReportGenerationService(ReportRequestPreparationService preparationService,
                                   AiChatService aiChatService,
                                   PromptTemplateService promptTemplateService,
                                   ReportPromptContextFactory promptContextFactory,
                                   ReportGenerationOutputValidator outputValidator,
                                   ReportOutputSanitizer outputSanitizer,
                                   ReportDraftPersistenceService draftPersistenceService) {
        this(preparationService, aiChatService, promptTemplateService, promptContextFactory,
                outputValidator, outputSanitizer, draftPersistenceService, null);
    }

    /** RUN-01/02：装配 TaskRunService 后，模型尝试、预算与时间线自动归属运行。 */
    public ReportGenerationService(ReportRequestPreparationService preparationService,
                                   AiChatService aiChatService,
                                   PromptTemplateService promptTemplateService,
                                   ReportPromptContextFactory promptContextFactory,
                                   ReportGenerationOutputValidator outputValidator,
                                   ReportOutputSanitizer outputSanitizer,
                                   ReportDraftPersistenceService draftPersistenceService,
                                   TaskRunService taskRunService) {
        this.preparationService = preparationService;
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.promptContextFactory = promptContextFactory;
        this.outputValidator = outputValidator;
        this.outputSanitizer = outputSanitizer;
        this.draftPersistenceService = draftPersistenceService;
        this.taskRunService = taskRunService;
    }

    public ReportDraftResponse generate(ReportGenerateRequest request) {
        return generate(request, null);
    }

    public ReportDraftResponse generate(ReportGenerateRequest request, ReportPublicationClaim claim) {
        var preview = preparationService.prepare(request);
        String modelName = aiChatService.modelName();
        if (preview.sources().isEmpty()) {
            return new ReportDraftResponse(null, preview.reportType(), preview.period(), preview.title(), "REJECTED", null,
                    List.of("生成报告至少需要一条有效来源。"), null, null, modelName, null);
        }
        RenderedPrompt prompt = promptTemplateService.renderWithMetadata(
                PROMPT_LOCATION, preview.templateVersion(), promptContextFactory.create(preview));
        if (taskRunService == null) {
            return executeGeneration(request, preview, prompt, modelName, AiAttemptObserver.noOp(), claim).response();
        }
        // RUN-01/02：运行关联——证据清单进入上下文，模型尝试与失败类别进入时间线。
        TaskRun run = taskRunService.startRun("report", "generation",
                "report-" + UUID.randomUUID(),
                new TaskRunBudget(3, 3, 50_000, Duration.ofMinutes(2)),
                new ContextManifest(
                        preview.sources().stream()
                                .map(source -> "source:" + source.sourceId()).toList(),
                        "approval:self", Duration.ofHours(1), Instant.now().plusSeconds(3600)));
        TaskStep step = taskRunService.beginStep(run.runId(), "generate-draft");
        GenerationOutcome outcome;
        try {
            outcome = executeGeneration(request, preview, prompt, modelName,
                    taskRunService.aiAttemptObserver(run.runId(), step.stepId()), claim);
        } catch (RuntimeException ex) {
            FailureCategory category = ex instanceof BusinessException business
                    ? RuntimeFailureCategories.from(business) : FailureCategory.MODEL;
            taskRunService.failStep(step, category, "报告草稿生成失败");
            taskRunService.failRun(run.runId(), category, "报告草稿生成失败");
            throw ex;
        }
        taskRunService.completeStep(step,
                preview.sources().stream().map(s0 -> "source:" + s0.sourceId()).toList(),
                "草稿状态：" + outcome.response().status());
        taskRunService.completeRun(run.runId());
        return outcome.response();
    }

    /** 包装结果：响应 + 模型调用元数据（供运行账本登记真实用量）。 */
    private record GenerationOutcome(ReportDraftResponse response, AiInvocationMetadata aiMetadata) {
    }

    private GenerationOutcome executeGeneration(ReportGenerateRequest request,
                                                ReportRequestPreparationService.ReportRequestPreview preview,
                                                RenderedPrompt prompt, String modelName,
                                                AiAttemptObserver attemptObserver, ReportPublicationClaim claim) {
        long startMs = System.currentTimeMillis();
        AiInvocationMetadata invocationMetadata = null;
        try {
            AiInvocationResult<LlmReportOutput> invocation = attemptObserver == AiAttemptObserver.noOp()
                    ? aiChatService.generateEvidenceJsonWithMetadata(
                            "report.generation", prompt.content(), LlmReportOutput.class)
                    : aiChatService.generateEvidenceJsonWithMetadata(
                            "report.generation", prompt.content(), LlmReportOutput.class, attemptObserver);
            LlmReportOutput output = outputValidator.sanitizeForReview(
                    invocation.content(), preview.sources());
            invocationMetadata = invocation.metadata();
            String effectiveModelName = invocationMetadata != null
                    && invocationMetadata.modelName() != null
                    ? invocationMetadata.modelName() : modelName;
            ReportGenerationOutputValidator.ValidationResult validation = outputValidator.validate(output, preview.sources());
            if (!validation.valid()) {
                var draft = claim == null ? draftPersistenceService.createNeedsReviewDraft(
                        preview, validation.violations(), effectiveModelName, prompt.metadata(),
                        invocationMetadata, POLICY_VERSION, System.currentTimeMillis() - startMs)
                        : draftPersistenceService.createNeedsReviewDraft(
                        preview, validation.violations(), effectiveModelName, prompt.metadata(),
                        invocationMetadata, POLICY_VERSION, System.currentTimeMillis() - startMs, claim);
                return new GenerationOutcome(new ReportDraftResponse(draft.id(), preview.reportType(), preview.period(), preview.title(),
                        draft.status().name(), null, validation.violations(), draft.confirmationToken(),
                        draft.expiresAt().toString(), effectiveModelName,
                        draftPersistenceService.reviewStatus(draft)), invocationMetadata);
            }
            LlmReportOutput sanitizedOutput = outputSanitizer.sanitize(output);
            var draft = claim == null ? draftPersistenceService.createDraft(
                    preview, sanitizedOutput, effectiveModelName, prompt.metadata(),
                    invocationMetadata, POLICY_VERSION, System.currentTimeMillis() - startMs)
                    : draftPersistenceService.createDraft(
                    preview, sanitizedOutput, effectiveModelName, prompt.metadata(),
                    invocationMetadata, POLICY_VERSION, System.currentTimeMillis() - startMs, claim);
            return new GenerationOutcome(new ReportDraftResponse(draft.id(), preview.reportType(), preview.period(), preview.title(), draft.status().name(),
                    sanitizedOutput, List.of(), draft.confirmationToken(),
                    draft.expiresAt().toString(), effectiveModelName,
                    draftPersistenceService.reviewStatus(draft)), invocationMetadata);
        } catch (RuntimeException ex) {
            draftPersistenceService.recordGenerationFailure(
                    preview, modelName, prompt.metadata(), invocationMetadata,
                    POLICY_VERSION, System.currentTimeMillis() - startMs);
            throw ex;
        }
    }
}
