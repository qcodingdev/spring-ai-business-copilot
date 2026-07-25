package dev.qcoding.businesscopilot.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只清理公网账号产生的临时数据，所有 systemManaged 预置记录永久保留。 */
@Service
@ConditionalOnProperty(
        prefix = "business-copilot",
        name = "runtime-mode",
        havingValue = "public-demo")
public class PublicDemoRetentionService {

    private static final Logger log = LoggerFactory.getLogger(PublicDemoRetentionService.class);
    private final JdbcTemplate jdbcTemplate;
    private final PublicDemoProperties properties;

    public PublicDemoRetentionService(
            JdbcTemplate jdbcTemplate,
            PublicDemoProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${business-copilot.public-demo.cleanup-delay:1h}")
    public CleanupSummary cleanup() {
        try {
            Instant now = Instant.now();
            Timestamp temporaryBefore = Timestamp.from(now.minus(properties.temporaryDataRetention()));
            Timestamp operationBefore = Timestamp.from(now.minus(properties.operationLogRetention()));
            LocalDate usageBefore = LocalDate.now(properties.zoneId())
                    .minusDays(Math.max(1, properties.usageRetention().toDays()));
            Map<String, Integer> rows = new LinkedHashMap<>();
            rows.put("queryCandidates", jdbcTemplate.update(
                    "DELETE FROM data_sql_candidates WHERE created_at < ?", temporaryBefore));
            rows.put("supportTickets", jdbcTemplate.update(
                    "DELETE FROM support_tickets WHERE system_managed = FALSE AND created_at < ?",
                    temporaryBefore));
            rows.put("reportRequests", jdbcTemplate.update(
                    "DELETE FROM report_requests WHERE system_managed = FALSE AND created_at < ?",
                    temporaryBefore));
            rows.put("resumeJobs", jdbcTemplate.update(
                    "DELETE FROM resume_jobs WHERE system_managed = FALSE AND created_at < ?",
                    temporaryBefore));
            rows.put("knowledgeDocuments", jdbcTemplate.update(
                    "DELETE FROM knowledge_documents WHERE system_managed = FALSE AND created_at < ?",
                    temporaryBefore));
            rows.put("expiredResetIntents", jdbcTemplate.update(
                    "DELETE FROM demo_reset_intents WHERE expires_at < ?", Timestamp.from(now)));
            rows.put("demoJobs", jdbcTemplate.update(
                    "DELETE FROM demo_data_jobs WHERE created_at < ?", operationBefore));
            rows.put("adminAudit", jdbcTemplate.update(
                    "DELETE FROM demo_admin_audit_logs WHERE created_at < ?", operationBefore));
            rows.put("clientUsage", jdbcTemplate.update(
                    "DELETE FROM public_demo_usage_daily WHERE usage_date < ?", Date.valueOf(usageBefore)));
            rows.put("aiUsage", jdbcTemplate.update(
                    "DELETE FROM ai_usage_daily WHERE usage_date < ?", Date.valueOf(usageBefore)));
            return new CleanupSummary(rows, rows.values().stream().mapToInt(Integer::intValue).sum());
        } catch (RuntimeException ex) {
            log.warn("公网体验临时数据清理失败，系统预置数据未受影响", ex);
            return new CleanupSummary(Map.of(), 0);
        }
    }

    public record CleanupSummary(Map<String, Integer> deletedByType, int totalDeleted) {
    }
}
