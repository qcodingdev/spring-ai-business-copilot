package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService;

import java.util.List;

/** Generates a review-only structured report candidate from prepared evidence. */
public class ReportGenerationService {

    private static final String PROMPT_LOCATION = "report-copilot/report-generation.st";

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
        if (preview.sources().isEmpty()) {
            return new ReportDraftResponse(null, preview.reportType(), preview.period(), preview.title(), "REJECTED", null,
                    List.of("At least one source is required to generate a report."), null, null, aiChatService.modelName());
        }
        String prompt = promptTemplateService.render(PROMPT_LOCATION, promptContextFactory.create(preview));
        LlmReportOutput output = aiChatService.generateJson(prompt, LlmReportOutput.class);
        ReportGenerationOutputValidator.ValidationResult validation = outputValidator.validate(output, preview.sources());
        if (!validation.valid()) {
            return new ReportDraftResponse(null, preview.reportType(), preview.period(), preview.title(), "REJECTED", null,
                    validation.violations(), null, null, aiChatService.modelName());
        }
        LlmReportOutput sanitizedOutput = outputSanitizer.sanitize(output);
        var draft = draftPersistenceService.createDraft(preview, sanitizedOutput, aiChatService.modelName());
        return new ReportDraftResponse(draft.id(), preview.reportType(), preview.period(), preview.title(), draft.status().name(),
                sanitizedOutput, List.of(), draft.confirmationToken(), draft.expiresAt().toString(), aiChatService.modelName());
    }
}
