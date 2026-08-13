package dev.qcoding.businesscopilot.supportcopilot.ticket;

import java.util.List;
import java.util.Optional;

import dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketSentiment;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketUrgency;

/**
 * Repository for {@link SupportTicket} persistence.
 *
 * <p>工单仓库接口。定义工单的创建、查询和状态更新操作。</p>
 */
public interface SupportTicketRepository {

    SupportTicket save(SupportTicket ticket);

    Optional<SupportTicket> findById(Long id);

    /** Atomically claims an imported or failed ticket for AI analysis without leaking object existence. */
    default Optional<SupportTicket> claimForAnalysis(Long id, String actorId, boolean admin) {
        return Optional.empty();
    }

    default boolean updateClassification(Long id, TicketCategory category,
                                         TicketSentiment sentiment, TicketUrgency urgency) {
        return false;
    }

    default boolean failAnalysis(Long id) {
        return false;
    }

    List<SupportTicket> findRecent(int limit);

    boolean transitionStatus(Long id, SupportTicketStatus expectedStatus, SupportTicketStatus targetStatus);

    long count();
}
