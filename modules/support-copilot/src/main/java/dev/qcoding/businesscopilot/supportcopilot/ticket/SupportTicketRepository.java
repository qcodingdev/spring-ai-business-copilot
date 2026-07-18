package dev.qcoding.businesscopilot.supportcopilot.ticket;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link SupportTicket} persistence.
 *
 * <p>工单仓库接口。定义工单的创建、查询和状态更新操作。</p>
 */
public interface SupportTicketRepository {

    SupportTicket save(SupportTicket ticket);

    Optional<SupportTicket> findById(Long id);

    List<SupportTicket> findRecent(int limit);

    boolean transitionStatus(Long id, SupportTicketStatus expectedStatus, SupportTicketStatus targetStatus);

    long count();
}
