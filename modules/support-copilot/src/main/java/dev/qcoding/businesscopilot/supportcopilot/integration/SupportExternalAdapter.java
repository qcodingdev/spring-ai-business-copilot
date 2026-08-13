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
