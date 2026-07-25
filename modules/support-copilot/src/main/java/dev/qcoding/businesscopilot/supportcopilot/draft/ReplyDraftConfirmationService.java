package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketStatus;
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
    private final SensitiveTextMasker sensitiveTextMasker;

    public ReplyDraftConfirmationService(SupportReplyDraftRepository draftRepository,
                                         SupportTicketRepository ticketRepository,
                                         SupportAuditService auditService,
                                         CurrentActorProvider actorProvider,
                                         ObjectAccessPolicy accessPolicy,
                                         ConfirmationTokenService tokenService,
                                         SensitiveTextMasker sensitiveTextMasker) {
        this.draftRepository = draftRepository;
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.tokenService = tokenService;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    @Transactional
    public ConfirmationResult confirm(Long draftId, String confirmationToken) {
        SupportReplyDraft draft = requireDraft(draftId);
        CurrentActor actor = actorProvider.currentActor();
        ObjectAction action = draft.reviewQueue() ? ObjectAction.REVIEW : ObjectAction.CONFIRM;
        requireAccess(draft, actor, action);
        validateTokenAndState(draft, confirmationToken);
        if (!draftRepository.transitionStatus(
                draftId, draft.status(), SupportDraftStatus.CONFIRMED,
                draft.editedDraftText() == null
                        ? SupportDecisionOutcome.ACCEPTED : SupportDecisionOutcome.EDITED_ACCEPTED,
                actor.actorId(), Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        SupportTicketStatus expectedTicketStatus = draft.reviewQueue()
                ? SupportTicketStatus.NEEDS_HUMAN : SupportTicketStatus.DRAFTED;
        if (!ticketRepository.transitionStatus(
                draft.ticketId(), expectedTicketStatus, SupportTicketStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "CONFIRMED",
                null, null, draft.riskLevel().name(), draft.citedChunkIds(), null,
                null, null, draft.ownerActorId(), actor.actorId(),
                null, null, null, null, null, null,
                null, null, null, null, null, null));
        return new ConfirmationResult(draftId, draft.ticketId(), SupportDraftStatus.CONFIRMED);
    }

    @Transactional
    public ConfirmationResult cancel(Long draftId, String confirmationToken) {
        SupportReplyDraft draft = requireDraft(draftId);
        CurrentActor actor = actorProvider.currentActor();
        requireAccess(draft, actor, ObjectAction.CANCEL);
        validateTokenAndState(draft, confirmationToken);
        if (!draftRepository.transitionStatus(
                draftId, draft.status(), SupportDraftStatus.CANCELED,
                SupportDecisionOutcome.REJECTED, actor.actorId(), Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        SupportTicketStatus expectedTicketStatus = draft.reviewQueue()
                ? SupportTicketStatus.NEEDS_HUMAN : SupportTicketStatus.DRAFTED;
        if (!ticketRepository.transitionStatus(
                draft.ticketId(), expectedTicketStatus, SupportTicketStatus.CANCELED)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "CANCELED",
                null, null, draft.riskLevel().name(), draft.citedChunkIds(), null,
                null, null, draft.ownerActorId(), actor.actorId(),
                null, null, null, null, null, null,
                null, null, null, null, null, null));
        return new ConfirmationResult(draftId, draft.ticketId(), SupportDraftStatus.CANCELED);
    }

    /** 打开队列项时重新签发凭证；数据库只替换摘要，不保存明文 token。 */
    @Transactional
    public ReviewSession openReviewSession(Long draftId) {
        SupportReplyDraft draft = requireDraft(draftId);
        CurrentActor actor = actorProvider.currentActor();
        ObjectAction action = draft.reviewQueue() ? ObjectAction.REVIEW : ObjectAction.CONFIRM;
        requireAccess(draft, actor, action);
        if (draft.status() != SupportDraftStatus.DRAFTED && draft.status() != SupportDraftStatus.NEEDS_REVIEW
                || draft.expiresAt() == null || !draft.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        if (!draftRepository.replaceConfirmationToken(
                draftId, draft.status(), token.digest(), actor.actorId(), Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String reply = draft.editedDraftText() == null ? draft.originalDraftText() : draft.editedDraftText();
        return new ReviewSession(draftId, reply, token.rawToken(), draft.status(), draft.expiresAt());
    }

    /** 记录人工已经通过外部渠道回复客户；不发送任何消息或调用外部系统。 */
    @Transactional
    public ConfirmationResult markCustomerReplied(Long draftId) {
        SupportReplyDraft draft = requireDraft(draftId);
        CurrentActor actor = actorProvider.currentActor();
        ObjectAction action = draft.reviewQueue() ? ObjectAction.REVIEW : ObjectAction.CONFIRM;
        requireAccess(draft, actor, action);
        if (draft.status() != SupportDraftStatus.CONFIRMED
                || !ticketRepository.transitionStatus(
                        draft.ticketId(), SupportTicketStatus.CONFIRMED, SupportTicketStatus.CLOSED)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "CUSTOMER_REPLY_RECORDED",
                null, null, draft.riskLevel().name(), draft.citedChunkIds(), null,
                null, null, draft.ownerActorId(), actor.actorId(),
                null, null, null, null, null, null,
                null, null, null, null, null, null));
        return new ConfirmationResult(draftId, draft.ticketId(), SupportDraftStatus.CONFIRMED);
    }

    @Transactional
    public EditResult edit(Long draftId, String editedText, String reason) {
        SupportReplyDraft draft = requireDraft(draftId);
        CurrentActor actor = actorProvider.currentActor();
        ObjectAction action = draft.reviewQueue() ? ObjectAction.REVIEW : ObjectAction.CONFIRM;
        requireAccess(draft, actor, action);
        if (draft.status() != SupportDraftStatus.DRAFTED
                && draft.status() != SupportDraftStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if (draft.expiresAt() == null || !draft.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String masked = sensitiveTextMasker.mask(editedText == null ? "" : editedText.trim());
        if (masked.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "人工修订后的草稿不能为空。");
        }
        Instant now = Instant.now();
        if (!draftRepository.edit(draftId, draft.status(), masked,
                sensitiveTextMasker.mask(reason), actor.actorId(), now)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "DRAFT_EDITED",
                null, null, draft.riskLevel().name(), draft.citedChunkIds(), null,
                null, null, draft.ownerActorId(), actor.actorId(),
                null, null, null, null, null, "support-human-feedback-v2",
                null, null, null, null, null, null));
        return new EditResult(draftId, masked, draft.status());
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
        if (draft.status() != SupportDraftStatus.DRAFTED
                && draft.status() != SupportDraftStatus.NEEDS_REVIEW) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if (!tokenService.matches(rawToken, draft.tokenDigest())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (draft.expiresAt() == null || !draft.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
    }

    public record ConfirmationResult(Long draftId, Long ticketId, SupportDraftStatus status) {
    }

    public record ReviewSession(Long draftId, String suggestedReply, String confirmationToken,
                                SupportDraftStatus status, Instant expiresAt) {
    }

    public record EditResult(Long draftId, String editedText, SupportDraftStatus status) {
    }
}
