package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 仅在 public-demo 可用的双确认恢复流程，删除范围限制在 demo 临时数据。 */
@Service
public class DemoDataResetService {

    public static final String CONFIRMATION_TEXT = "恢复公网演示初始数据";
    private static final Duration INTENT_TTL = Duration.ofMinutes(10);
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final RuntimeModeProperties runtimeModeProperties;
    private final ConfirmationTokenService tokenService;
    private final CurrentActorProvider actorProvider;
    private final DemoDataJobRepository jobRepository;
    private final DemoDataInitializationService initializationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public DemoDataResetService(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            RuntimeModeProperties runtimeModeProperties,
            ConfirmationTokenService tokenService,
            CurrentActorProvider actorProvider,
            DemoDataJobRepository jobRepository,
            DemoDataInitializationService initializationService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.runtimeModeProperties = runtimeModeProperties;
        this.tokenService = tokenService;
        this.actorProvider = actorProvider;
        this.jobRepository = jobRepository;
        this.initializationService = initializationService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ResetIntent createIntent() {
        requirePublicDemo();
        String actorId = actorProvider.currentActor().actorId();
        Map<String, Integer> counts = deletionCounts();
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        Instant expiresAt = Instant.now().plus(INTENT_TTL);
        jdbcTemplate.update("""
                INSERT INTO demo_reset_intents (
                    token_digest, requested_by, deletion_counts, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """, token.digest(), actorId, write(counts), Timestamp.from(expiresAt),
                Timestamp.from(Instant.now()));
        return new ResetIntent(counts, token.rawToken(), expiresAt, CONFIRMATION_TEXT);
    }

    public DemoDataJob reset(String rawToken, String confirmationText) {
        requirePublicDemo();
        if (!CONFIRMATION_TEXT.equals(confirmationText)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "恢复确认文案不正确。");
        }
        String actorId = actorProvider.currentActor().actorId();
        String digest = tokenService.digest(rawToken);
        int consumed = jdbcTemplate.update("""
                UPDATE demo_reset_intents
                SET consumed_at = ?
                WHERE token_digest = ? AND requested_by = ?
                  AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(Instant.now()), digest, actorId, Timestamp.from(Instant.now()));
        if (consumed != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "恢复凭证无效、已使用或已过期。");
        }

        DemoDataJob job = jobRepository.create(DemoDataJob.JobType.RESET, actorId);
        jobRepository.running(job.id());
        try {
            ResetSummary summary = performReset();
            jobRepository.completed(job.id(), write(summary));
            audit(actorId, summary.totalDeleted(), "COMPLETED", write(summary));
        } catch (RuntimeException ex) {
            jobRepository.failed(job.id(), safeCategory(ex));
            audit(actorId, 0, "FAILED", safeCategory(ex));
        }
        return jobRepository.find(job.id()).orElse(job);
    }

    protected ResetSummary performReset() {
        Map<String, Integer> deleted = transactionTemplate.execute(status -> resetDatabase());
        DemoDataInitializationService.SeedSummary restored = initializationService.seedAll();
        return new ResetSummary(deleted == null ? Map.of() : deleted, restored);
    }

    private Map<String, Integer> resetDatabase() {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("sqlCandidates", jdbcTemplate.update("DELETE FROM data_sql_candidates"));
        deleted.put("supportTickets", jdbcTemplate.update(
                "DELETE FROM support_tickets WHERE system_managed = FALSE"));
        deleted.put("resumeJobs", jdbcTemplate.update(
                "DELETE FROM resume_jobs WHERE system_managed = FALSE"));
        deleted.put("reportRequests", jdbcTemplate.update(
                "DELETE FROM report_requests WHERE system_managed = FALSE"));
        deleted.put("knowledgeDocuments", jdbcTemplate.update(
                "DELETE FROM knowledge_documents WHERE system_managed = FALSE"));

        restoreBusinessData();
        return deleted;
    }

    private void restoreBusinessData() {
        jdbcTemplate.update("DELETE FROM refunds WHERE id >= 1000");
        jdbcTemplate.update("DELETE FROM order_items WHERE id >= 1000");
        jdbcTemplate.update("DELETE FROM orders WHERE id >= 1000");
        jdbcTemplate.update("DELETE FROM products WHERE id >= 1000");
        jdbcTemplate.update("DELETE FROM customers WHERE id >= 1000");
        jdbcTemplate.update("DELETE FROM marketing_events WHERE id >= 1000");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V18__expand_fictional_sample_business_data.sql"));
        DatabasePopulatorUtils.execute(populator, dataSource);
        initializationService.markDataSystemManaged();
    }

    private Map<String, Integer> deletionCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("queryCandidates", count("data_sql_candidates", "TRUE"));
        counts.put("temporarySupportTickets", count("support_tickets", "system_managed = FALSE"));
        counts.put("temporaryResumeJobs", count("resume_jobs", "system_managed = FALSE"));
        counts.put("temporaryReports", count("report_requests", "system_managed = FALSE"));
        counts.put("temporaryKnowledgeDocuments", count("knowledge_documents", "system_managed = FALSE"));
        return counts;
    }

    private int count(String table, String predicate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
        return count == null ? 0 : count;
    }

    private void requirePublicDemo() {
        if (runtimeModeProperties.mode() != RuntimeMode.PUBLIC_DEMO) {
            throw new BusinessException(ErrorCode.DEMO_SCENARIO_NOT_AVAILABLE,
                    "恢复操作只在 public-demo 模式开放。");
        }
    }

    private void audit(String actorId, int affected, String result, String summary) {
        jdbcTemplate.update("""
                INSERT INTO demo_admin_audit_logs (
                    actor_id, action, scope_summary, affected_count, result, created_at
                ) VALUES (?, 'RESET', ?, ?, ?, ?)
                """, actorId, summary, affected, result, Timestamp.from(Instant.now()));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("恢复范围序列化失败", ex);
        }
    }

    private String safeCategory(Throwable ex) {
        String value = ex.getClass().getSimpleName();
        return value.length() <= 80 ? value : "RESET_ERROR";
    }

    public record ResetIntent(
            Map<String, Integer> willDelete,
            String resetToken,
            Instant expiresAt,
            String requiredConfirmationText) {
    }

    public record ResetSummary(
            Map<String, Integer> deleted,
            DemoDataInitializationService.SeedSummary restored) {
        int totalDeleted() {
            return deleted.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
