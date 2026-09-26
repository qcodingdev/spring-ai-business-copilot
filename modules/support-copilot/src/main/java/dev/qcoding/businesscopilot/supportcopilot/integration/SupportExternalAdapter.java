package dev.qcoding.businesscopilot.supportcopilot.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 外部客服系统只读工单和人工确认后内部备注回写边界。 */
public interface SupportExternalAdapter {

    boolean supports(SupportExternalProvider provider);

    List<ExternalTicket> fetchRecent(SupportExternalConnection connection, int limit);

    void writeConfirmedDraft(
            SupportExternalConnection connection,
            String externalTicketId,
            String sanitizedDraft,
            String idempotencyKey);

    /**
     * SUP-03：供应商回执查询适配。实现方按幂等键向外部系统核对回写结果；
     * 无法核对时返回 empty，调用方必须保留未知状态，不得猜测。
     */
    default java.util.Optional<ExternalWritebackReceipt> fetchWritebackReceipt(
            SupportExternalConnection connection,
            String externalTicketId,
            String idempotencyKey) {
        return java.util.Optional.empty();
    }

    /** 外部系统核对结果：delivered 明确成功/失败；两者皆非则保持未知。 */
    record ExternalWritebackReceipt(boolean delivered, String receipt) {
    }

    record ExternalTicket(
            String externalId,
            String customerMessage,
            String channel,
            Instant updatedAt,
            Instant slaDueAt,
            Map<String, Object> customerContext,
            Map<String, Object> orderContext,
            Map<String, Object> serviceContext) {
    }
}
