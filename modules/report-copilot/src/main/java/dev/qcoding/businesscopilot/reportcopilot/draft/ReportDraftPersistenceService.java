package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;
import java.util.List;

/** Creates a request, its sanitized evidence, and a draft state as one database transaction. */
public class ReportDraftPersistenceService {

    private final ReportDraftRepository draftRepository;
    private final ReportAuditService auditService;
    private final ReportCopilotProperties properties;

    public ReportDraftPersistenceService(ReportDraftRepository draftRepository, ReportAuditService auditService,
                                         ReportCopilotProperties properties) {
        this.draftRepository = draftRepository;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional
    public ReportDraft createDraft(ReportRequestPreparationService.ReportRequestPreview preview,
                                   LlmReportOutput content, String modelName) {
        return createDraft(preview, content, modelName, null, null, null, null);
    }

    @Transactional
    public ReportDraft createDraft(ReportRequestPreparationService.ReportRequestPreview preview,
                                   LlmReportOutput content, String modelName,
                                   PromptTemplateMetadata promptMetadata,
                                   AiInvocationMetadata aiMetadata,
                                   String policyVersion, Long latencyMs) {
        ReportDraft draft = draftRepository.save(preview, content, modelName, properties.draftTtl());
        String citedSourceIds = content.citations().stream().map(citation -> citation.sourceId()).distinct()
                .collect(Collectors.joining(","));
        auditService.record(new ReportAuditLog(
                draft.requestId(), draft.id(), "DRAFTED", preview.sources().size(),
                citedSourceIds, modelName, ReportDraftStatus.DRAFTED.name(), null,
                latencyMs, draft.ownerActorId(), null,
                aiMetadata != null ? aiMetadata.providerName() : null,
                aiMetadata != null ? aiMetadata.providerRequestId() : null,
                promptMetadata != null ? promptMetadata.name() : null,
                promptMetadata != null ? promptMetadata.version() : null,
                promptMetadata != null ? promptMetadata.contentHash() : null,
                policyVersion, null,
                aiMetadata != null ? aiMetadata.inputTokens() : null,
                aiMetadata != null ? aiMetadata.outputTokens() : null,
                aiMetadata != null ? aiMetadata.finishReason() : null));
        return draft;
    }

    @Transactional
    public ReportDraft createNeedsReviewDraft(ReportRequestPreparationService.ReportRequestPreview preview,
                                              List<String> reviewReasons, String modelName) {
        return createNeedsReviewDraft(preview, reviewReasons, modelName,
                null, null, null, null);
    }

    @Transactional
    public ReportDraft createNeedsReviewDraft(ReportRequestPreparationService.ReportRequestPreview preview,
                                              List<String> reviewReasons, String modelName,
                                              PromptTemplateMetadata promptMetadata,
                                              AiInvocationMetadata aiMetadata,
                                              String policyVersion, Long latencyMs) {
        ReportDraft draft = draftRepository.saveNeedsReview(preview, reviewReasons, modelName, properties.draftTtl());
        auditService.record(new ReportAuditLog(
                draft.requestId(), draft.id(), "NEEDS_REVIEW", preview.sources().size(),
                "", modelName, ReportDraftStatus.NEEDS_REVIEW.name(), null,
                latencyMs, draft.ownerActorId(), null,
                aiMetadata != null ? aiMetadata.providerName() : null,
                aiMetadata != null ? aiMetadata.providerRequestId() : null,
                promptMetadata != null ? promptMetadata.name() : null,
                promptMetadata != null ? promptMetadata.version() : null,
                promptMetadata != null ? promptMetadata.contentHash() : null,
                policyVersion, "REPORT_OUTPUT_VALIDATION",
                aiMetadata != null ? aiMetadata.inputTokens() : null,
                aiMetadata != null ? aiMetadata.outputTokens() : null,
                aiMetadata != null ? aiMetadata.finishReason() : null));
        return draft;
    }

    public void recordGenerationFailure(ReportRequestPreparationService.ReportRequestPreview preview, String modelName) {
        recordGenerationFailure(preview, modelName, null, null, null, null);
    }

    public void recordGenerationFailure(ReportRequestPreparationService.ReportRequestPreview preview,
                                        String modelName, PromptTemplateMetadata promptMetadata,
                                        AiInvocationMetadata aiMetadata, String policyVersion,
                                        Long latencyMs) {
        auditService.record(new ReportAuditLog(
                null, null, "FAILED", preview.sources().size(), "", modelName,
                ReportDraftStatus.FAILED.name(), null, latencyMs, null, null,
                aiMetadata != null ? aiMetadata.providerName() : null,
                aiMetadata != null ? aiMetadata.providerRequestId() : null,
                promptMetadata != null ? promptMetadata.name() : null,
                promptMetadata != null ? promptMetadata.version() : null,
                promptMetadata != null ? promptMetadata.contentHash() : null,
                policyVersion, "MODEL_GENERATION_FAILED",
                aiMetadata != null ? aiMetadata.inputTokens() : null,
                aiMetadata != null ? aiMetadata.outputTokens() : null,
                aiMetadata != null ? aiMetadata.finishReason() : null));
    }
}
