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

    /** SUP-02：记录转人工原因（服务端按分支确定性标注）。 */
    default boolean updateHandoffReason(Long id, SupportHandoffReason reason) {
        return false;
    }

    /** SUP-01：读取工单的追问建议 JSON（缺失要素由确定性服务生成）。 */
    default java.util.Optional<String> findFollowUps(Long id) {
        return java.util.Optional.empty();
    }

    /** SUP-01：保存工单的追问建议（JSON 序列化由仓库实现负责）。 */
    default boolean saveFollowUps(Long id, java.util.List<String> questions) {
        return false;
    }

    /** SUP-02：读取工单的转人工原因（未转人工时为空）。 */
    default java.util.Optional<SupportHandoffReason> findHandoffReason(Long id) {
        return java.util.Optional.empty();
    }

    long count();
}
