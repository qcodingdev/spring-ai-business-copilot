package dev.qcoding.businesscopilot.reportcopilot.draft;

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
        ReportDraft draft = draftRepository.save(preview, content, modelName, properties.draftTtl());
        String citedSourceIds = content.citations().stream().map(citation -> citation.sourceId()).distinct()
                .collect(Collectors.joining(","));
        auditService.record(new ReportAuditLog(draft.requestId(), draft.id(), "DRAFTED", preview.sources().size(),
                citedSourceIds, modelName, ReportDraftStatus.DRAFTED.name(), null));
        return draft;
    }

    @Transactional
    public ReportDraft createNeedsReviewDraft(ReportRequestPreparationService.ReportRequestPreview preview,
                                              List<String> reviewReasons, String modelName) {
        ReportDraft draft = draftRepository.saveNeedsReview(preview, reviewReasons, modelName, properties.draftTtl());
        auditService.record(new ReportAuditLog(draft.requestId(), draft.id(), "NEEDS_REVIEW", preview.sources().size(),
                "", modelName, ReportDraftStatus.NEEDS_REVIEW.name(), "Evidence validation requires manual review."));
        return draft;
    }

    public void recordGenerationFailure(ReportRequestPreparationService.ReportRequestPreview preview, String modelName) {
        auditService.record(new ReportAuditLog(null, null, "FAILED", preview.sources().size(), "", modelName,
                ReportDraftStatus.FAILED.name(), "Model generation failed."));
    }
}
