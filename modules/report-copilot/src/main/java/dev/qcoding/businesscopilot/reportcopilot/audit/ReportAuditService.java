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
            recordRequired(event);
        } catch (RuntimeException ex) {
            log.error("报表模块审计事件写入失败：eventType={}，draftId={}", event.eventType(), event.draftId(), ex);
        }
    }

    public void recordRequired(ReportAuditLog event) {
        jdbcTemplate.update("INSERT INTO report_audit_logs (request_id, http_request_id, actor_id, "
                        + "draft_id, event_type, source_count, cited_source_ids, model_name, status, "
                        + "error_message, latency_ms, creator_actor_id, action_actor_id, provider_name, "
                        + "provider_request_id, prompt_name, prompt_version, prompt_hash, policy_version, "
                        + "violation_codes, input_tokens, output_tokens, finish_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.requestId(), BusinessRequestContextHolder.currentRequestId(),
                BusinessRequestContextHolder.currentActorId(), event.draftId(), event.eventType(),
                event.sourceCount(), event.citedSourceIds(), event.modelName(), event.status(),
                event.errorMessage(), event.latencyMs(), event.creatorActorId(), event.actionActorId(),
                event.providerName(), event.providerRequestId(), event.promptName(),
                event.promptVersion(), event.promptHash(), event.policyVersion(),
                event.violationCodes(), event.inputTokens(), event.outputTokens(), event.finishReason());
    }
}
