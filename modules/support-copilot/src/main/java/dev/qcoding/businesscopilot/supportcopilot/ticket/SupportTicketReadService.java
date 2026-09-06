package dev.qcoding.businesscopilot.supportcopilot.ticket;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 读取工单复核信息时统一执行对象归属校验，避免控制器直接暴露仓储。 */
public class SupportTicketReadService {

    private final SupportTicketRepository repository;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    public SupportTicketReadService(SupportTicketRepository repository,
                                    CurrentActorProvider actorProvider,
                                    ObjectAccessPolicy accessPolicy,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.objectMapper = objectMapper;
    }

    public List<String> followUps(long ticketId) {
        requireAccessible(ticketId);
        return repository.findFollowUps(ticketId)
                .map(json -> objectMapper.readValue(json, new TypeReference<List<String>>() { }))
                .orElse(List.of());
    }

    public SupportHandoffReason handoffReason(long ticketId) {
        requireAccessible(ticketId);
        return repository.findHandoffReason(ticketId).orElse(null);
    }

    private SupportTicket requireAccessible(long ticketId) {
        SupportTicket ticket = repository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CurrentActor actor = actorProvider.currentActor();
        boolean ownerAccess = accessPolicy.allowed(actor, ObjectAction.CONFIRM,
                ticket.ownerActorId(), null, false);
        boolean reviewerAccess = accessPolicy.allowed(actor, ObjectAction.REVIEW,
                ticket.ownerActorId(), null, true);
        if (!ownerAccess && !reviewerAccess) {
            // 不向越权调用方泄露对象是否存在。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return ticket;
    }
}
