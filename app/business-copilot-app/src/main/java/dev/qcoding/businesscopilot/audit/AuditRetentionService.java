package dev.qcoding.businesscopilot.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/** 先匿名化敏感审计明细，再删除超过保留期的审计元数据。 */
@Service
public class AuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);
    private final JdbcTemplate jdbcTemplate;
    private final AuditRetentionProperties properties;

    public AuditRetentionService(JdbcTemplate jdbcTemplate, AuditRetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Scheduled(cron = "${business-copilot.audit.retention.cleanup-cron:0 15 3 * * *}")
    public CleanupResult cleanup() {
        try {
            Instant now = Instant.now();
            Timestamp anonymizeBefore = Timestamp.from(now.minus(properties.anonymizeAfter()));
            Timestamp deleteBefore = Timestamp.from(now.minus(properties.deleteAfter()));

            int anonymized = 0;
            anonymized += jdbcTemplate.update(
                    "UPDATE query_audit_logs SET user_question = NULL, generated_sql = NULL, final_sql = NULL, "
                            + "validation_errors = NULL, error_message = NULL, anonymized_at = ? "
                            + "WHERE anonymized_at IS NULL AND created_at <= ?",
                    Timestamp.from(now), anonymizeBefore);
            anonymized += jdbcTemplate.update(
                    "UPDATE knowledge_qa_audit_logs SET question = NULL, refusal_reason = NULL, anonymized_at = ? "
                            + "WHERE anonymized_at IS NULL AND created_at <= ?",
                    Timestamp.from(now), anonymizeBefore);
            anonymized += anonymizeErrors("support_audit_logs", now, anonymizeBefore);
            anonymized += anonymizeErrors("report_audit_logs", now, anonymizeBefore);
            anonymized += anonymizeErrors("resume_audit_logs", now, anonymizeBefore);

            int deleted = deleteExpired("query_audit_logs", deleteBefore)
                    + deleteExpired("knowledge_qa_audit_logs", deleteBefore)
                    + deleteExpired("support_audit_logs", deleteBefore)
                    + deleteExpired("report_audit_logs", deleteBefore)
                    + deleteExpired("resume_audit_logs", deleteBefore);
            return new CleanupResult(anonymized, deleted);
        } catch (RuntimeException ex) {
            log.warn("审计保留数据清理失败，业务状态未受影响", ex);
            return new CleanupResult(0, 0);
        }
    }

    private int anonymizeErrors(String table, Instant now, Timestamp anonymizeBefore) {
        return jdbcTemplate.update(
                "UPDATE " + table + " SET error_message = NULL, anonymized_at = ? "
                        + "WHERE anonymized_at IS NULL AND created_at <= ?",
                Timestamp.from(now), anonymizeBefore);
    }

    private int deleteExpired(String table, Timestamp deleteBefore) {
        return jdbcTemplate.update("DELETE FROM " + table + " WHERE created_at <= ?", deleteBefore);
    }

    public record CleanupResult(int anonymizedRows, int deletedRows) {
    }
}
