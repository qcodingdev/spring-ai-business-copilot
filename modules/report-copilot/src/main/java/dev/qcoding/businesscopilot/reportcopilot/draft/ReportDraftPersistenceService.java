package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata;
import dev.qcoding.businesscopilot.commonsecurity.IndependentReviewService;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.enterprise.ReportLifecycleService;
import dev.qcoding.businesscopilot.reportcopilot.enterprise.ReportPublicationClaim;
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
    private final IndependentReviewService reviewService;
    private final ReportLifecycleService lifecycle;

    public ReportDraftPersistenceService(ReportDraftRepository draftRepository, ReportAuditService auditService,
                                         ReportCopilotProperties properties) {
        this(draftRepository, auditService, properties, null);
    }

    public ReportDraftPersistenceService(ReportDraftRepository draftRepository, ReportAuditService auditService,
                                         ReportCopilotProperties properties,
                                         IndependentReviewService reviewService) {
        this(draftRepository, auditService, properties, reviewService, null);
    }

    public ReportDraftPersistenceService(ReportDraftRepository draftRepository, ReportAuditService auditService,
                                         ReportCopilotProperties properties, IndependentReviewService reviewService,
                                         ReportLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.draftRepository = draftRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.reviewService = reviewService;
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
        return createDraft(preview, content, modelName, promptMetadata, aiMetadata, policyVersion, latencyMs, null);
    }

    @Transactional
    public ReportDraft createDraft(ReportRequestPreparationService.ReportRequestPreview preview,
                                   LlmReportOutput content, String modelName, PromptTemplateMetadata promptMetadata,
                                   AiInvocationMetadata aiMetadata, String policyVersion, Long latencyMs,
                                   ReportPublicationClaim claim) {
        if (claim != null) lifecycle.lockPublication(claim);
        ReportDraft draft = draftRepository.save(preview, content, modelName, properties.draftTtl());
        registerReview(draft);
        if (claim != null) lifecycle.completePublication(claim, draft);
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
        return createNeedsReviewDraft(preview, reviewReasons, modelName, promptMetadata, aiMetadata, policyVersion, latencyMs, null);
    }

    @Transactional
    public ReportDraft createNeedsReviewDraft(ReportRequestPreparationService.ReportRequestPreview preview,
                                              List<String> reviewReasons, String modelName, PromptTemplateMetadata promptMetadata,
                                              AiInvocationMetadata aiMetadata, String policyVersion, Long latencyMs,
                                              ReportPublicationClaim claim) {
        if (claim != null) lifecycle.lockPublication(claim);
        ReportDraft draft = draftRepository.saveNeedsReview(preview, reviewReasons, modelName, properties.draftTtl());
        registerReview(draft);
        if (claim != null) lifecycle.completePublication(claim, draft);
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

    private void registerReview(ReportDraft draft) {
        if (reviewService != null) {
            reviewService.register(IndependentReviewService.SubjectType.REPORT_DRAFT,
                    String.valueOf(draft.id()), draft.ownerActorId());
        }
    }

    public String reviewStatus(ReportDraft draft) {
        if (reviewService == null) return IndependentReviewService.Status.APPROVED.name();
        return reviewService.status(IndependentReviewService.SubjectType.REPORT_DRAFT,
                String.valueOf(draft.id())).status().name();
    }
}
