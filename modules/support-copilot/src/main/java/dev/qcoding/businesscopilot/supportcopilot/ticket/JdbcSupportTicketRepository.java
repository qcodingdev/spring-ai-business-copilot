package dev.qcoding.businesscopilot.supportcopilot.ticket;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link SupportTicketRepository}.
 *
 * <p>customerMessage 入库前通过 SensitiveTextMasker 脱敏，确保审计表和数据库
 * 不存储未脱敏的客户原文。</p>
 */
public class JdbcSupportTicketRepository implements SupportTicketRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveTextMasker sensitiveTextMasker;

    private static final RowMapper<SupportTicket> ROW_MAPPER = (rs, rowNum) -> new SupportTicket(
            rs.getLong("id"),
            rs.getString("external_id"),
            rs.getString("customer_message"),
            rs.getString("channel"),
            dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory.valueOf(rs.getString("category")),
            dev.qcoding.businesscopilot.supportcopilot.classification.TicketSentiment.valueOf(rs.getString("sentiment")),
            dev.qcoding.businesscopilot.supportcopilot.classification.TicketUrgency.valueOf(rs.getString("urgency")),
            SupportTicketStatus.valueOf(rs.getString("status")),
            rs.getString("owner_actor_id"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);

    public JdbcSupportTicketRepository(JdbcTemplate jdbcTemplate, SensitiveTextMasker sensitiveTextMasker) {
        this.jdbcTemplate = jdbcTemplate;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    @Override
    public SupportTicket save(SupportTicket ticket) {
        String maskedMessage = sensitiveTextMasker.mask(ticket.customerMessage());
        String sql = "INSERT INTO support_tickets (external_id, customer_message, channel, category, sentiment, urgency, status, owner_actor_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, ticket.externalId());
            ps.setString(2, maskedMessage);
            ps.setString(3, ticket.channel());
            ps.setString(4, ticket.category().name());
            ps.setString(5, ticket.sentiment().name());
            ps.setString(6, ticket.urgency().name());
            ps.setString(7, ticket.status().name());
            ps.setString(8, ticket.ownerActorId());
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keyHolder);

        long id = keyHolder.getKey().longValue();
        return new SupportTicket(id, ticket.externalId(), maskedMessage,
                ticket.channel(), ticket.category(), ticket.sentiment(),
                ticket.urgency(), ticket.status(), ticket.ownerActorId(), now, now);
    }

    @Override
    public Optional<SupportTicket> findById(Long id) {
        List<SupportTicket> results = jdbcTemplate.query(
                "SELECT * FROM support_tickets WHERE id = ?", ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<SupportTicket> findRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM support_tickets ORDER BY created_at DESC LIMIT ?",
                ROW_MAPPER, limit);
    }

    @Override
    public boolean transitionStatus(Long id, SupportTicketStatus expectedStatus, SupportTicketStatus targetStatus) {
        int rows = jdbcTemplate.update(
                "UPDATE support_tickets SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                targetStatus.name(), Timestamp.from(Instant.now()), id, expectedStatus.name());
        return rows == 1;
    }

    @Override
    public long count() {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_tickets", Long.class);
        return result != null ? result : 0;
    }
}
