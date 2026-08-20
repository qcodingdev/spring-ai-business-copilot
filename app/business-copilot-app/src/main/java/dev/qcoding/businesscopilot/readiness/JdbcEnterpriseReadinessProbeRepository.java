package dev.qcoding.businesscopilot.readiness;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes all five-module probes against one consistent set of time boundaries. */
@Repository
public class JdbcEnterpriseReadinessProbeRepository implements EnterpriseReadinessProbeRepository {

    private static final String PROBE_SQL = """
            WITH boundaries AS (
                SELECT ?::timestamptz AS now_at,
                       ?::timestamptz AS stale_before,
                       ?::timestamptz AS expired_result_before,
                       ?::timestamptz AS review_before,
                       ?::timestamptz AS failed_since
            )
            SELECT
                (SELECT COUNT(*) FROM data_report_handoffs handoff
                 WHERE handoff.status = 'CLAIMED'
                   AND (handoff.claim_token IS NULL
                        OR handoff.claimed_at IS NULL
                        OR handoff.claimed_at <= boundary.stale_before)) AS data_stale_handoff_claims,
                (SELECT COUNT(*) FROM data_query_results result
                 WHERE result.expires_at <= boundary.expired_result_before) AS data_expired_results,
                (SELECT COUNT(*) FROM knowledge_sync_runs run
                 WHERE run.status = 'RUNNING'
                   AND run.started_at <= boundary.stale_before) AS knowledge_stale_sync_runs,
                (SELECT COUNT(*) FROM knowledge_sync_runs run
                 WHERE run.status = 'FAILED'
                   AND run.started_at >= boundary.failed_since
                   AND NOT EXISTS (
                       SELECT 1 FROM knowledge_sync_runs recovery
                       WHERE recovery.connection_id = run.connection_id
                         AND recovery.status = 'COMPLETED'
                         AND recovery.started_at > run.started_at
                   )) AS knowledge_failed_sync_runs,
                (SELECT COUNT(*) FROM knowledge_documents document
                 WHERE document.current_version = TRUE
                   AND (document.index_status = 'FAILED'
                        OR (document.index_status IN ('PENDING', 'PROCESSING', 'RETRYABLE')
                            AND document.updated_at <= boundary.stale_before)
                        OR (document.enabled = TRUE
                            AND (document.expires_at <= boundary.now_at
                                 OR document.conflict_status <> 'NONE'))))
                    AS knowledge_blocked_documents,
                (SELECT COUNT(*) FROM support_draft_writebacks writeback
                 WHERE writeback.status = 'UNKNOWN') AS support_unknown_writebacks,
                (SELECT COUNT(*) FROM support_draft_writebacks writeback
                 WHERE writeback.status = 'PROCESSING'
                   AND COALESCE(writeback.last_attempt_at, writeback.updated_at, writeback.created_at)
                       <= boundary.stale_before) AS support_stale_writebacks,
                (SELECT COUNT(*) FROM support_tickets ticket
                 WHERE ticket.sla_status = 'BREACHED'
                   AND ticket.status NOT IN ('CLOSED', 'CANCELED')) AS support_breached_sla,
                (SELECT COUNT(*) FROM report_schedules schedule
                 WHERE schedule.enabled = TRUE
                   AND (schedule.claim_token IS NOT NULL OR schedule.claimed_at IS NOT NULL)
                   AND (schedule.claim_token IS NULL
                        OR schedule.claimed_at IS NULL
                        OR schedule.claimed_at <= boundary.stale_before)) AS report_stale_schedule_claims,
                (SELECT COUNT(*) FROM report_schedule_runs run
                 WHERE run.status = 'FAILED'
                   AND run.started_at >= boundary.failed_since
                   AND NOT EXISTS (
                       SELECT 1 FROM report_schedule_runs recovery
                       WHERE recovery.schedule_id = run.schedule_id
                         AND recovery.status IN ('DRAFTED', 'NEEDS_REVIEW')
                         AND recovery.started_at > run.started_at
                   )) AS report_failed_runs,
                (SELECT COUNT(*) FROM report_drafts draft
                 WHERE draft.status IN ('DRAFTED', 'NEEDS_REVIEW')
                   AND draft.created_at <= boundary.review_before) AS report_overdue_reviews,
                (SELECT COUNT(*) FROM resume_assessments assessment
                 WHERE assessment.status IN ('DRAFTED', 'NEEDS_REVIEW')
                   AND assessment.created_at <= boundary.review_before) AS hr_overdue_assessment_reviews,
                (SELECT COUNT(*) FROM hr_onboarding_tasks task
                 JOIN hr_onboarding_instances instance ON instance.id = task.instance_id
                 WHERE instance.status = 'IN_PROGRESS'
                   AND task.required = TRUE
                   AND task.status = 'PENDING'
                   AND task.created_at <= boundary.review_before) AS hr_overdue_onboarding_tasks
            FROM boundaries boundary
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcEnterpriseReadinessProbeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Long> probe(Instant now, EnterpriseReadinessProperties properties) {
        Map<String, Long> result = jdbcTemplate.queryForObject(PROBE_SQL, (rs, rowNum) -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("DATA_STALE_HANDOFF_CLAIMS", rs.getLong("data_stale_handoff_claims"));
            counts.put("DATA_EXPIRED_RESULTS", rs.getLong("data_expired_results"));
            counts.put("KNOWLEDGE_STALE_SYNC_RUNS", rs.getLong("knowledge_stale_sync_runs"));
            counts.put("KNOWLEDGE_FAILED_SYNC_RUNS", rs.getLong("knowledge_failed_sync_runs"));
            counts.put("KNOWLEDGE_BLOCKED_DOCUMENTS", rs.getLong("knowledge_blocked_documents"));
            counts.put("SUPPORT_UNKNOWN_WRITEBACKS", rs.getLong("support_unknown_writebacks"));
            counts.put("SUPPORT_STALE_WRITEBACKS", rs.getLong("support_stale_writebacks"));
            counts.put("SUPPORT_BREACHED_SLA", rs.getLong("support_breached_sla"));
            counts.put("REPORT_STALE_SCHEDULE_CLAIMS", rs.getLong("report_stale_schedule_claims"));
            counts.put("REPORT_FAILED_RUNS", rs.getLong("report_failed_runs"));
            counts.put("REPORT_OVERDUE_REVIEWS", rs.getLong("report_overdue_reviews"));
            counts.put("HR_OVERDUE_ASSESSMENT_REVIEWS", rs.getLong("hr_overdue_assessment_reviews"));
            counts.put("HR_OVERDUE_ONBOARDING_TASKS", rs.getLong("hr_overdue_onboarding_tasks"));
            return counts;
        }, Timestamp.from(now),
                Timestamp.from(now.minus(properties.staleOperationAfter())),
                Timestamp.from(now.minus(properties.expiredResultGrace())),
                Timestamp.from(now.minus(properties.reviewBacklogAfter())),
                Timestamp.from(now.minus(properties.failedRunLookback())));
        if (result == null) {
            throw new IllegalStateException("企业运行就绪检查未返回结果");
        }
        return result;
    }
}
