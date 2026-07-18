package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Owner-authorized digest-backed confirmation and cancellation for report drafts. */
public class ReportDraftConfirmationService {

    private final ReportDraftRepository draftRepository;
    private final ReportAuditService auditService;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final ConfirmationTokenService tokenService;

    public ReportDraftConfirmationService(ReportDraftRepository draftRepository,
                                          ReportAuditService auditService,
                                          CurrentActorProvider actorProvider,
                                          ObjectAccessPolicy accessPolicy,
                                          ConfirmationTokenService tokenService) {
        this.draftRepository = draftRepository;
        this.auditService = auditService;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.tokenService = tokenService;
    }

    @Transactional
    public ConfirmationResult confirm(Long draftId, String token) {
        ReportDraft draft = resolveDraft(draftId, token, ObjectAction.CONFIRM);
        if (draft.status() != ReportDraftStatus.DRAFTED) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        CurrentActor actor = actorProvider.currentActor();
        if (!draftRepository.transitionStatus(
                draftId, ReportDraftStatus.DRAFTED, ReportDraftStatus.CONFIRMED, actor.actorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draftId, "CONFIRMED", 0, null, null,
                ReportDraftStatus.CONFIRMED.name(), null, null,
                draft.ownerActorId(), actor.actorId(), null, null,
                null, null, null, null, null, null, null, null));
        return new ConfirmationResult(draftId, ReportDraftStatus.CONFIRMED);
    }

    @Transactional
    public ConfirmationResult cancel(Long draftId, String token) {
        ReportDraft draft = resolveDraft(draftId, token, ObjectAction.CANCEL);
        if (draft.status() != ReportDraftStatus.DRAFTED
                && draft.status() != ReportDraftStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        CurrentActor actor = actorProvider.currentActor();
        if (!draftRepository.transitionStatus(
                draftId, draft.status(), ReportDraftStatus.CANCELED, actor.actorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draftId, "CANCELED", 0, null, null,
                ReportDraftStatus.CANCELED.name(), null, null,
                draft.ownerActorId(), actor.actorId(), null, null,
                null, null, null, null, null, null, null, null));
        return new ConfirmationResult(draftId, ReportDraftStatus.CANCELED);
    }

    private ReportDraft resolveDraft(Long draftId, String rawToken, ObjectAction action) {
        ReportDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, action, draft.ownerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!tokenService.matches(rawToken, draft.tokenDigest())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (draft.expiresAt() == null || !draft.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        return draft;
    }

    public record ConfirmationResult(Long draftId, ReportDraftStatus status) {
    }
}
