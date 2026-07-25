package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 登录后业务总览的安全投影，不返回正文、数据库 ID、Prompt 或模型信息。 */
@Service
public class DemoOverviewService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider actorProvider;
    private final DemoScenarioRepository scenarioRepository;

    public DemoOverviewService(
            JdbcTemplate jdbcTemplate,
            CurrentActorProvider actorProvider,
            DemoScenarioRepository scenarioRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
        this.scenarioRepository = scenarioRepository;
    }

    public Overview overview() {
        String actorId = actorProvider.currentActor().actorId();
        long knowledgeTotal = count(
                "SELECT COUNT(*) FROM knowledge_documents WHERE system_managed = TRUE AND current_version = TRUE");
        long knowledgeReady = count("""
                SELECT COUNT(*) FROM knowledge_documents
                WHERE system_managed = TRUE AND current_version = TRUE
                  AND index_status IN ('INDEXED', 'TEXT_ONLY')
                """);
        long sampleRows = count(
                "SELECT COUNT(*) FROM customers WHERE system_managed = TRUE");
        long pending = pendingReviews(actorId);
        return new Overview(
                scenarioRepository.countEnabled(),
                pending,
                new Readiness(knowledgeReady, knowledgeTotal, sampleRows),
                recentTasks(actorId));
    }

    private List<RecentTask> recentTasks(String actorId) {
        return jdbcTemplate.query("""
                SELECT task_type, status, created_at
                FROM (
                    SELECT '客服处理' AS task_type, status, created_at
                    FROM support_tickets
                    WHERE system_managed = TRUE OR owner_actor_id = ?
                    UNION ALL
                    SELECT '报告草稿', d.status, d.created_at
                    FROM report_drafts d
                    JOIN report_requests r ON r.id = d.request_id
                    WHERE r.system_managed = TRUE OR r.owner_actor_id = ?
                    UNION ALL
                    SELECT '招聘辅助', a.status, a.created_at
                    FROM resume_assessments a
                    JOIN resume_jobs j ON j.id = a.job_id
                    WHERE j.system_managed = TRUE OR j.owner_actor_id = ?
                    UNION ALL
                    SELECT '数据查询', status, created_at
                    FROM data_sql_candidates
                    WHERE owner_actor_id = ?
                ) tasks
                ORDER BY created_at DESC
                LIMIT 8
                """, (rs, rowNum) -> new RecentTask(
                rs.getString("task_type"),
                businessStatus(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant()),
                actorId, actorId, actorId, actorId);
    }

    private long pendingReviews(String actorId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM support_tickets
                     WHERE status = 'NEEDS_HUMAN'
                       AND (system_managed = TRUE OR owner_actor_id = ?))
                  + (SELECT COUNT(*) FROM report_drafts d
                     JOIN report_requests r ON r.id = d.request_id
                     WHERE d.status = 'NEEDS_REVIEW'
                       AND (r.system_managed = TRUE OR r.owner_actor_id = ?))
                  + (SELECT COUNT(*) FROM resume_assessments a
                     JOIN resume_jobs j ON j.id = a.job_id
                     WHERE a.status = 'NEEDS_REVIEW'
                       AND (j.system_managed = TRUE OR j.owner_actor_id = ?))
                """, Long.class, actorId, actorId, actorId);
        return value == null ? 0 : value;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String businessStatus(String status) {
        if (status == null) return "待处理";
        return switch (status) {
            case "NEEDS_HUMAN", "NEEDS_REVIEW" -> "待人工复核";
            case "DRAFTED", "PENDING_EXECUTION", "CRITERIA_DRAFTED" -> "待确认";
            case "CONFIRMED", "REVIEWED", "EXECUTED" -> "已确认";
            case "CANCELED", "CANCELLED" -> "已取消";
            case "FAILED", "GUARDRAIL_FAILED", "EXECUTION_FAILED" -> "处理失败";
            default -> "处理中";
        };
    }

    public record Overview(
            long availableScenarios,
            long pendingReviews,
            Readiness readiness,
            List<RecentTask> recentTasks) {
    }

    public record Readiness(long knowledgeReady, long knowledgeTotal, long fictionalCustomerRows) {
    }

    public record RecentTask(String taskType, String status, Instant createdAt) {
    }
}
