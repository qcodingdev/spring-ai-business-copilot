package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService;

import java.util.List;

/** Generates a review-only structured report candidate from prepared evidence. */
public class ReportGenerationService {

    private static final String PROMPT_LOCATION = "report-copilot/report-generation.st";
    private static final String POLICY_VERSION = "report-evidence-guardrails-v1";

    private final ReportRequestPreparationService preparationService;
    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final ReportPromptContextFactory promptContextFactory;
    private final ReportGenerationOutputValidator outputValidator;
    private final ReportOutputSanitizer outputSanitizer;
    private final ReportDraftPersistenceService draftPersistenceService;

    public ReportGenerationService(ReportRequestPreparationService preparationService,
                                   AiChatService aiChatService,
                                   PromptTemplateService promptTemplateService,
                                   ReportPromptContextFactory promptContextFactory,
                                   ReportGenerationOutputValidator outputValidator,
                                   ReportOutputSanitizer outputSanitizer,
                                   ReportDraftPersistenceService draftPersistenceService) {
        this.preparationService = preparationService;
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.promptContextFactory = promptContextFactory;
        this.outputValidator = outputValidator;
        this.outputSanitizer = outputSanitizer;
        this.draftPersistenceService = draftPersistenceService;
    }

    public ReportDraftResponse generate(ReportGenerateRequest request) {
        var preview = preparationService.prepare(request);
        String modelName = aiChatService.modelName();
        if (preview.sources().isEmpty()) {
            return new ReportDraftResponse(null, preview.reportType(), preview.period(), preview.title(), "REJECTED", null,
                    List.of("At least one source is required to generate a report."), null, null, modelName);
        }
        RenderedPrompt prompt = promptTemplateService.renderWithMetadata(
                PROMPT_LOCATION, "v1", promptContextFactory.create(preview));
        long startMs = System.currentTimeMillis();
        AiInvocationMetadata invocationMetadata = null;
        try {
            AiInvocationResult<LlmReportOutput> invocation = aiChatService.generateJsonWithMetadata(
                    prompt.content(), LlmReportOutput.class);
            LlmReportOutput output = invocation.content();
            invocationMetadata = invocation.metadata();
            String effectiveModelName = invocationMetadata != null
                    && invocationMetadata.modelName() != null
                    ? invocationMetadata.modelName() : modelName;
            ReportGenerationOutputValidator.ValidationResult validation = outputValidator.validate(output, preview.sources());
            if (!validation.valid()) {
                var draft = draftPersistenceService.createNeedsReviewDraft(
                        preview, validation.violations(), effectiveModelName, prompt.metadata(),
                        invocationMetadata, POLICY_VERSION, System.currentTimeMillis() - startMs);
                return new ReportDraftResponse(draft.id(), preview.reportType(), preview.period(), preview.title(),
                        draft.status().name(), null, validation.violations(), draft.confirmationToken(),
                        draft.expiresAt().toString(), effectiveModelName);
            }
            LlmReportOutput sanitizedOutput = outputSanitizer.sanitize(output);
            var draft = draftPersistenceService.createDraft(
                    preview, sanitizedOutput, effectiveModelName, prompt.metadata(),
                    invocationMetadata, POLICY_VERSION, System.currentTimeMillis() - startMs);
            return new ReportDraftResponse(draft.id(), preview.reportType(), preview.period(), preview.title(), draft.status().name(),
                    sanitizedOutput, List.of(), draft.confirmationToken(),
                    draft.expiresAt().toString(), effectiveModelName);
        } catch (RuntimeException ex) {
            draftPersistenceService.recordGenerationFailure(
                    preview, modelName, prompt.metadata(), invocationMetadata,
                    POLICY_VERSION, System.currentTimeMillis() - startMs);
            throw ex;
        }
    }
}
