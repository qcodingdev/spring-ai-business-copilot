package dev.qcoding.businesscopilot.supportcopilot.classification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 工单分析与分类请求。
 *
 * <p>工单分析请求。customerMessage 必须非空且不超过配置的 max-ticket-length。</p>
 *
 * @param customerMessage 脱敏前的客户原始消息
 * @param channel         来源渠道，例如网页、邮件、聊天或示例
 */
public record TicketClassificationRequest(
        @NotBlank(message = "客户消息不能为空。")
        @Size(max = 2000, message = "客户消息不能超过 2000 个字符。")
        String customerMessage,

        String channel) {
}
