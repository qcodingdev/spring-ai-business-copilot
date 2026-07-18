package dev.qcoding.businesscopilot.supportcopilot.audit;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JDBC implementation of {@link SupportAuditRepository}.
 *
 * <p>审计日志不记录客户原文和草稿内容，只记录元信息。</p>
 */
public class JdbcSupportAuditRepository implements SupportAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<SupportAuditLog> ROW_MAPPER = (rs, rowNum) -> new SupportAuditLog(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getObject("ticket_id") != null ? rs.getLong("ticket_id") : null,
            rs.getString("event_type"),
            rs.getString("category"),
            rs.getString("urgency"),
            rs.getString("risk_level"),
            rs.getString("cited_chunk_ids"),
            rs.getString("model_name"),
            rs.getObject("latency_ms") != null ? rs.getLong("latency_ms") : null,
            rs.getString("error_message"),
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
            rs.getTimestamp("anonymized_at") != null
                    ? rs.getTimestamp("anonymized_at").toInstant() : null,
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);

    public JdbcSupportAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SupportAuditLog save(SupportAuditLog log) {
        String sql = "INSERT INTO support_audit_logs (request_id, http_request_id, actor_id, "
                + "ticket_id, event_type, category, urgency, risk_level, cited_chunk_ids, "
                + "model_name, latency_ms, error_message, creator_actor_id, action_actor_id, "
                + "provider_name, provider_request_id, prompt_name, prompt_version, prompt_hash, "
                + "policy_version, violation_codes, input_tokens, output_tokens, finish_reason, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, log.requestId());
            ps.setString(2, BusinessRequestContextHolder.currentRequestId());
            ps.setString(3, BusinessRequestContextHolder.currentActorId());
            if (log.ticketId() != null) {
                ps.setLong(4, log.ticketId());
            } else {
                ps.setNull(4, java.sql.Types.BIGINT);
            }
            ps.setString(5, log.eventType());
            ps.setString(6, log.category());
            ps.setString(7, log.urgency());
            ps.setString(8, log.riskLevel());
            ps.setString(9, log.citedChunkIds());
            ps.setString(10, log.modelName());
            if (log.latencyMs() != null) {
                ps.setLong(11, log.latencyMs());
            } else {
                ps.setNull(11, java.sql.Types.BIGINT);
            }
            ps.setString(12, log.errorMessage());
            ps.setString(13, log.creatorActorId());
            ps.setString(14, log.actionActorId());
            ps.setString(15, log.providerName());
            ps.setString(16, log.providerRequestId());
            ps.setString(17, log.promptName());
            ps.setString(18, log.promptVersion());
            ps.setString(19, log.promptHash());
            ps.setString(20, log.policyVersion());
            ps.setString(21, log.violationCodes());
            if (log.inputTokens() != null) {
                ps.setInt(22, log.inputTokens());
            } else {
                ps.setNull(22, java.sql.Types.INTEGER);
            }
            if (log.outputTokens() != null) {
                ps.setInt(23, log.outputTokens());
            } else {
                ps.setNull(23, java.sql.Types.INTEGER);
            }
            ps.setString(24, log.finishReason());
            ps.setTimestamp(25, Timestamp.from(now));
            return ps;
        }, keyHolder);

        long id = keyHolder.getKey().longValue();
        return new SupportAuditLog(id, log.requestId(), log.ticketId(),
                log.eventType(), log.category(), log.urgency(), log.riskLevel(),
                log.citedChunkIds(), log.modelName(), log.latencyMs(),
                log.errorMessage(), log.creatorActorId(), log.actionActorId(),
                log.providerName(), log.providerRequestId(), log.promptName(),
                log.promptVersion(), log.promptHash(), log.policyVersion(),
                log.violationCodes(), log.inputTokens(), log.outputTokens(),
                log.finishReason(), null, now);
    }

    @Override
    public List<SupportAuditLog> findRecent(int page, int size) {
        int offset = page * size;
        return jdbcTemplate.query(
                "SELECT * FROM support_audit_logs ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, size, offset);
    }

    @Override
    public long count() {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_audit_logs", Long.class);
        return result != null ? result : 0;
    }
}
