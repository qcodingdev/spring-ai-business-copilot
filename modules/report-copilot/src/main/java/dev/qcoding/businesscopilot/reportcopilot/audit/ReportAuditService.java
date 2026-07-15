package dev.qcoding.businesscopilot.reportcopilot.audit;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fail-open audit writer: a logging outage must not leave a confirmed draft half-transitioned. */
public class ReportAuditService {

    private static final Logger log = LoggerFactory.getLogger(ReportAuditService.class);
    private final JdbcTemplate jdbcTemplate;

    public ReportAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(ReportAuditLog event) {
        try {
            jdbcTemplate.update("INSERT INTO report_audit_logs (request_id, http_request_id, actor_id, draft_id, event_type, source_count, cited_source_ids, model_name, status, error_message) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", event.requestId(),
                    BusinessRequestContextHolder.currentRequestId(), BusinessRequestContextHolder.currentActorId(),
                    event.draftId(), event.eventType(),
                    event.sourceCount(), event.citedSourceIds(), event.modelName(), event.status(), event.errorMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to persist Report Copilot audit event: eventType={}, draftId={}", event.eventType(), event.draftId(), ex);
        }
    }
}
