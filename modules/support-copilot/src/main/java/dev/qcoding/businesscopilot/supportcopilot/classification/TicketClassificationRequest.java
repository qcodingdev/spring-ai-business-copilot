package dev.qcoding.businesscopilot.supportcopilot.classification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request for ticket analysis and classification.
 *
 * <p>工单分析请求。customerMessage 必须非空且不超过配置的 max-ticket-length。</p>
 *
 * @param customerMessage the customer's original message before masking
 * @param channel         source channel (web, email, chat, sample)
 */
public record TicketClassificationRequest(
        @NotBlank(message = "customerMessage 不能为空")
        @Size(max = 2000, message = "customerMessage 长度超过限制")
        String customerMessage,

        String channel) {
}
