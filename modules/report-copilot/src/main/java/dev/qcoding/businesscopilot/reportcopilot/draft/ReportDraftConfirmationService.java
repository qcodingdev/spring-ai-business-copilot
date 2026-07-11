package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;

import java.time.Instant;

/** Confirms or cancels a persisted draft using only a server-generated one-time token. */
public class ReportDraftConfirmationService {

    private final ReportDraftRepository draftRepository;
    private final ReportAuditService auditService;

    public ReportDraftConfirmationService(ReportDraftRepository draftRepository, ReportAuditService auditService) {
        this.draftRepository = draftRepository;
        this.auditService = auditService;
    }

    public ConfirmationResult confirm(Long draftId, String token) {
        ReportDraft draft = resolveDraft(draftId, token);
        if (!draftRepository.transitionStatus(draftId, ReportDraftStatus.DRAFTED, ReportDraftStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The report draft cannot be confirmed in its current state.");
        }
        auditService.record(new ReportAuditLog(draft.requestId(), draftId, "CONFIRMED", 0, null, null,
                ReportDraftStatus.CONFIRMED.name(), null));
        return new ConfirmationResult(draftId, ReportDraftStatus.CONFIRMED);
    }

    public ConfirmationResult cancel(Long draftId, String token) {
        ReportDraft draft = resolveDraft(draftId, token);
        if (!draftRepository.transitionStatus(draftId, ReportDraftStatus.DRAFTED, ReportDraftStatus.CANCELED)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The report draft cannot be canceled in its current state.");
        }
        auditService.record(new ReportAuditLog(draft.requestId(), draftId, "CANCELED", 0, null, null,
                ReportDraftStatus.CANCELED.name(), null));
        return new ConfirmationResult(draftId, ReportDraftStatus.CANCELED);
    }

    private ReportDraft resolveDraft(Long draftId, String token) {
        ReportDraft draft = draftRepository.findByConfirmationToken(token).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "Invalid confirmation token or processed report draft."));
        if (!draft.id().equals(draftId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Confirmation token does not match the report draft.");
        }
        if (draft.status() != ReportDraftStatus.DRAFTED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Only DRAFTED reports can be confirmed or canceled.");
        }
        if (draft.expiresAt() == null || !draft.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The confirmation token has expired.");
        }
        return draft;
    }

    public record ConfirmationResult(Long draftId, ReportDraftStatus status) {
    }
}
