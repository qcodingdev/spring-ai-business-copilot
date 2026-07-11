package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

/** Creates a request, its sanitized evidence, and a DRAFTED report as one database transaction. */
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
}
