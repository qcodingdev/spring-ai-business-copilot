package dev.qcoding.businesscopilot.supportcopilot.ticket;

import java.time.Instant;

/**
 * Immutable domain model for a customer support ticket.
 *
 * <p>支持工单。记录脱敏后的客户消息、渠道、分类、情绪、紧急程度和状态。
 * customerMessage 入库前必须通过 SensitiveTextMasker 脱敏。</p>
 *
 * @param id              primary key
 * @param externalId      optional external system ticket identifier
 * @param customerMessage masked customer message
 * @param channel         source channel (web, email, chat, sample)
 * @param category        classification category
 * @param sentiment       detected sentiment
 * @param urgency         detected urgency level
 * @param status          current ticket status
 * @param createdAt       creation timestamp
 * @param updatedAt       last update timestamp
 */
public record SupportTicket(
        Long id,
        String externalId,
        String customerMessage,
        String channel,
        String category,
        String sentiment,
        String urgency,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
