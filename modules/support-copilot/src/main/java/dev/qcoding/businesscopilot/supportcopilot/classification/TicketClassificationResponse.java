package dev.qcoding.businesscopilot.supportcopilot.classification;

import java.util.List;

/**
 * Structured output from ticket classification model call.
 *
 * <p>工单分类模型输出。模型返回结构化 JSON，包含分类、情绪、紧急程度、
 * 摘要、是否需转人工及原因。</p>
 *
 * @param category    classification category
 * @param sentiment   detected customer sentiment
 * @param urgency     urgency level
 * @param summary     concise summary of the ticket issue
 * @param needsHuman  whether this ticket should be handled by a human
 * @param reasons     reasons for the classification and needsHuman decision
 */
public record TicketClassificationResponse(
        TicketCategory category,
        TicketSentiment sentiment,
        TicketUrgency urgency,
        String summary,
        boolean needsHuman,
        List<String> reasons) {
}
