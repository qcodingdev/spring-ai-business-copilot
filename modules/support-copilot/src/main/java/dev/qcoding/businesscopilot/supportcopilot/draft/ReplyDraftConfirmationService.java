package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for confirming or canceling reply drafts via server-side tokens.
 *
 * <p>回复草稿确认服务。确认接口只接收 confirmationToken，不接收草稿正文。
 * MVP 中确认只记录审计事件，不对外发送消息。</p>
 */
public class ReplyDraftConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ReplyDraftConfirmationService.class);

    private final SupportReplyDraftRepository draftRepository;
    private final SupportTicketRepository ticketRepository;
    private final SupportAuditService auditService;

    public ReplyDraftConfirmationService(SupportReplyDraftRepository draftRepository,
                                         SupportTicketRepository ticketRepository,
                                         SupportAuditService auditService) {
        this.draftRepository = draftRepository;
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
    }

    /**
     * Confirm a reply draft using the server-generated confirmation token.
     *
     * <p>只接收 token，不信任客户端传递的草稿正文。token 过期后不可确认。</p>
     *
     * @param draftId          the draft ID (from URL path)
     * @param confirmationToken the server-generated token
     * @return confirmation result
     */
    public ConfirmationResult confirm(Long draftId, String confirmationToken) {
        Optional<SupportReplyDraft> draftOpt = draftRepository.findByConfirmationToken(confirmationToken);

        if (draftOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "无效的确认 token 或草稿已被处理");
        }

        SupportReplyDraft draft = draftOpt.get();

        if (!draft.id().equals(draftId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认 token 与草稿 ID 不匹配");
        }

        if (draft.expiresAt() != null && draft.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认 token 已过期（过期时间: " + draft.expiresAt() + "）");
        }

        draftRepository.markConfirmed(draftId);
        ticketRepository.updateStatus(draft.ticketId(), "CONFIRMED");
        auditService.record(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "CONFIRMED",
                null, null, draft.riskLevel(), draft.citedChunkIds(), null,
                null, null, null));
        log.info("Reply draft {} confirmed via token for ticket {}", draftId, draft.ticketId());

        return new ConfirmationResult(draftId, draft.ticketId(), "CONFIRMED");
    }

    /**
     * Cancel a reply draft using the server-generated confirmation token.
     *
     * @param draftId          the draft ID (from URL path)
     * @param confirmationToken the server-generated token
     * @return cancellation result
     */
    public ConfirmationResult cancel(Long draftId, String confirmationToken) {
        Optional<SupportReplyDraft> draftOpt = draftRepository.findByConfirmationToken(confirmationToken);

        if (draftOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "无效的确认 token 或草稿已被处理");
        }

        SupportReplyDraft draft = draftOpt.get();

        if (!draft.id().equals(draftId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认 token 与草稿 ID 不匹配");
        }

        draftRepository.markCanceled(draftId);
        ticketRepository.updateStatus(draft.ticketId(), "CANCELED");
        auditService.record(new SupportAuditLog(
                null, UUID.randomUUID().toString(), draft.ticketId(), "CANCELED",
                null, null, draft.riskLevel(), draft.citedChunkIds(), null,
                null, null, null));
        log.info("Reply draft {} canceled via token for ticket {}", draftId, draft.ticketId());

        return new ConfirmationResult(draftId, draft.ticketId(), "CANCELED");
    }

    /**
     * Result of a draft confirmation or cancellation.
     */
    public record ConfirmationResult(Long draftId, Long ticketId, String status) {
    }
}
