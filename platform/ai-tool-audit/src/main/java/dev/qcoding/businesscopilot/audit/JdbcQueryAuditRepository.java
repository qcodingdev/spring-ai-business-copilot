package dev.qcoding.businesscopilot.audit;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * JDBC-backed implementation of {@link QueryAuditRepository}.
 *
 * <p>基于 Spring JDBC 的审计日志持久化。审计表由 Flyway 迁移脚本创建
 * （见 app 模块的 db/migration/V3__create_query_audit_logs.sql）。</p>
 *
 * <p>安全说明：审计日志只记录元信息（用户问题、SQL、状态、行数、耗时、错误），
 * 不记录完整查询结果，也不记录敏感字段的明文值。</p>
 */
public class JdbcQueryAuditRepository implements QueryAuditRepository {

    private static final String INSERT_SQL = """
            INSERT INTO query_audit_logs (
                request_id, http_request_id, actor_id, user_question, generated_sql, final_sql,
                validation_status, validation_errors, confirmed,
                execution_status, row_count, error_message,
                model_name, latency_ms, creator_actor_id, action_actor_id,
                provider_name, provider_request_id, prompt_name, prompt_version, prompt_hash,
                policy_version, violation_codes, input_tokens, output_tokens, finish_reason, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_RECENT_SQL = """
            SELECT id, request_id, http_request_id, actor_id, user_question, generated_sql, final_sql,
                   validation_status, validation_errors, confirmed,
                   execution_status, row_count, error_message,
                   model_name, latency_ms, creator_actor_id, action_actor_id,
                   provider_name, provider_request_id, prompt_name, prompt_version, prompt_hash,
                   policy_version, violation_codes, input_tokens, output_tokens, finish_reason,
                   anonymized_at, created_at
            FROM query_audit_logs
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM query_audit_logs";

    private static final RowMapper<QueryAuditLog> ROW_MAPPER = (rs, rowNum) -> new QueryAuditLog(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("http_request_id"),
            rs.getString("actor_id"),
            rs.getString("user_question"),
            rs.getString("generated_sql"),
            rs.getString("final_sql"),
            rs.getString("validation_status"),
            rs.getString("validation_errors"),
            rs.getBoolean("confirmed"),
            rs.getString("execution_status"),
            (Integer) rs.getObject("row_count"),
            rs.getString("error_message"),
            rs.getString("model_name"),
            (Long) rs.getObject("latency_ms"),
            rs.getString("creator_actor_id"),
            rs.getString("action_actor_id"),
            rs.getString("provider_name"),
            rs.getString("provider_request_id"),
            rs.getString("prompt_name"),
            rs.getString("prompt_version"),
            rs.getString("prompt_hash"),
            rs.getString("policy_version"),
            rs.getString("violation_codes"),
            (Integer) rs.getObject("input_tokens"),
            (Integer) rs.getObject("output_tokens"),
            rs.getString("finish_reason"),
            rs.getTimestamp("anonymized_at") != null ? rs.getTimestamp("anonymized_at").toInstant() : null,
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);

    private final JdbcTemplate jdbcTemplate;

    public JdbcQueryAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Long save(AuditEvent event) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        return jdbcTemplate.queryForObject(INSERT_SQL, Long.class,
                event.requestId(),
                BusinessRequestContextHolder.currentRequestId(),
                BusinessRequestContextHolder.currentActorId(),
                event.userQuestion(),
                event.generatedSql(),
                event.finalSql(),
                event.status() != null ? event.status().name() : null,
                event.validationErrors(),
                event.confirmed(),
                event.eventType() != null ? event.eventType().name() : null,
                event.rowCount(),
                event.errorMessage(),
                event.modelName(),
                event.latencyMs(),
                event.creatorActorId() != null ? event.creatorActorId()
                        : BusinessRequestContextHolder.currentActorId(),
                event.actionActorId(),
                event.providerName(),
                event.providerRequestId(),
                event.promptName(),
                event.promptVersion(),
                event.promptHash(),
                event.policyVersion(),
                event.violationCodes(),
                event.inputTokens(),
                event.outputTokens(),
                event.finishReason(),
                now);
    }

    @Override
    public List<QueryAuditLog> findRecent(int page, int size) {
        int offset = Math.max(0, page) * size;
        return jdbcTemplate.query(FIND_RECENT_SQL, ROW_MAPPER, size, offset);
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        return count != null ? count : 0L;
    }
}
