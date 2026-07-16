package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Object-authorized, digest-backed support draft confirmation lifecycle. */
public class ReplyDraftConfirmationService {

    private final SupportReplyDraftRepository draftRepository;
    private final SupportTicketRepository ticketRepository;
    private final SupportAuditService auditService;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final ConfirmationTokenService tokenService;

    public ReplyDraftConfirmationService(SupportReplyDraftRepository draftRepository,
                                         SupportTicketRepository ticketRepository,
                                         SupportAuditService auditService,
                                         CurrentActorProvider actorProvider,
                                         ObjectAccessPolicy accessPolicy,
                                         ConfirmationTokenService tokenService) {
        this.draftRepository = draftRepository;
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.tokenService = tokenService;
    }

    @Transactional
    public ConfirmationResult confirm(Long draftId, String confirmationToken) {
        SupportReplyDraft draft = requireDraft(draftId);
        CurrentActor actor = actorProvider.currentActor();
        ObjectAction action = draft.reviewQueue() ? ObjectAction.REVIEW : ObjectAction.CONFIRM;
        requireAccess(draft, actor, action);
        validateTokenAndState(draft, confirmationToken);
        if (!draftRepository.transitionStatus(
                draftId, draft.status(), "CONFIRMED", actor.actorId(), Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String expectedTicketStatus = draft.reviewQueue() ? "NEEDS_HUMAN" : "DRAFTED";
        if (!ticketRepository.transitionStatus(draft.ticketId(), expectedTicketStatus, "CONFIRMED")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "CONFIRMED",
                null, null, draft.riskLevel(), draft.citedChunkIds(), null,
                null, null, draft.ownerActorId(), actor.actorId(),
                null, null, null, null, null, null,
                null, null, null, null, null, null));
        return new ConfirmationResult(draftId, draft.ticketId(), "CONFIRMED");
    }

    @Transactional
    public ConfirmationResult cancel(Long draftId, String confirmationToken) {
        SupportReplyDraft draft = requireDraft(draftId);
        CurrentActor actor = actorProvider.currentActor();
        requireAccess(draft, actor, ObjectAction.CANCEL);
        validateTokenAndState(draft, confirmationToken);
        if (!draftRepository.transitionStatus(
                draftId, draft.status(), "CANCELED", actor.actorId(), Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String expectedTicketStatus = draft.reviewQueue() ? "NEEDS_HUMAN" : "DRAFTED";
        if (!ticketRepository.transitionStatus(draft.ticketId(), expectedTicketStatus, "CANCELED")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "CANCELED",
                null, null, draft.riskLevel(), draft.citedChunkIds(), null,
                null, null, draft.ownerActorId(), actor.actorId(),
                null, null, null, null, null, null,
                null, null, null, null, null, null));
        return new ConfirmationResult(draftId, draft.ticketId(), "CANCELED");
    }

    private SupportReplyDraft requireDraft(Long draftId) {
        return draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void requireAccess(SupportReplyDraft draft, CurrentActor actor, ObjectAction action) {
        if (!accessPolicy.allowed(actor, action, draft.ownerActorId(),
                draft.reviewerActorId(), draft.reviewQueue())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private void validateTokenAndState(SupportReplyDraft draft, String rawToken) {
        if (!"DRAFTED".equals(draft.status()) && !"NEEDS_REVIEW".equals(draft.status())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if (!tokenService.matches(rawToken, draft.tokenDigest())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (draft.expiresAt() == null || !draft.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
    }

    public record ConfirmationResult(Long draftId, Long ticketId, String status) {
    }
}
