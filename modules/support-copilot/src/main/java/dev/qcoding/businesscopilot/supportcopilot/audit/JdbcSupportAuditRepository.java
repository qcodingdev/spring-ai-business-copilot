package dev.qcoding.businesscopilot.supportcopilot.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);

    public JdbcSupportAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SupportAuditLog save(SupportAuditLog log) {
        String sql = "INSERT INTO support_audit_logs (request_id, ticket_id, event_type, category, urgency, risk_level, cited_chunk_ids, model_name, latency_ms, error_message, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, log.requestId());
            if (log.ticketId() != null) {
                ps.setLong(2, log.ticketId());
            } else {
                ps.setNull(2, java.sql.Types.BIGINT);
            }
            ps.setString(3, log.eventType());
            ps.setString(4, log.category());
            ps.setString(5, log.urgency());
            ps.setString(6, log.riskLevel());
            ps.setString(7, log.citedChunkIds());
            ps.setString(8, log.modelName());
            if (log.latencyMs() != null) {
                ps.setLong(9, log.latencyMs());
            } else {
                ps.setNull(9, java.sql.Types.BIGINT);
            }
            ps.setString(10, log.errorMessage());
            ps.setTimestamp(11, Timestamp.from(now));
            return ps;
        }, keyHolder);

        long id = keyHolder.getKey().longValue();
        return new SupportAuditLog(id, log.requestId(), log.ticketId(),
                log.eventType(), log.category(), log.urgency(), log.riskLevel(),
                log.citedChunkIds(), log.modelName(), log.latencyMs(),
                log.errorMessage(), now);
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
