package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link SupportReplyDraftRepository}.
 *
 * <p>draftText 入库前通过 SensitiveTextMasker 脱敏。</p>
 */
public class JdbcSupportReplyDraftRepository implements SupportReplyDraftRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveTextMasker sensitiveTextMasker;

    private static final RowMapper<SupportReplyDraft> ROW_MAPPER = (rs, rowNum) -> new SupportReplyDraft(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getString("draft_text"),
            rs.getString("cited_chunk_ids"),
            rs.getString("risk_level"),
            rs.getString("risk_reasons"),
            rs.getString("confirmation_token"),
            rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);

    public JdbcSupportReplyDraftRepository(JdbcTemplate jdbcTemplate, SensitiveTextMasker sensitiveTextMasker) {
        this.jdbcTemplate = jdbcTemplate;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    @Override
    public SupportReplyDraft save(SupportReplyDraft draft) {
        String maskedDraft = sensitiveTextMasker.mask(draft.draftText());
        String sql = "INSERT INTO support_reply_drafts (ticket_id, draft_text, cited_chunk_ids, risk_level, risk_reasons, confirmation_token, expires_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, draft.ticketId());
            ps.setString(2, maskedDraft);
            ps.setString(3, draft.citedChunkIds());
            ps.setString(4, draft.riskLevel());
            ps.setString(5, draft.riskReasons());
            ps.setString(6, draft.confirmationToken());
            ps.setTimestamp(7, Timestamp.from(draft.expiresAt()));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);

        long id = keyHolder.getKey().longValue();
        return new SupportReplyDraft(id, draft.ticketId(), maskedDraft,
                draft.citedChunkIds(), draft.riskLevel(), draft.riskReasons(),
                draft.confirmationToken(), draft.expiresAt(), now);
    }

    @Override
    public Optional<SupportReplyDraft> findById(Long id) {
        List<SupportReplyDraft> results = jdbcTemplate.query(
                "SELECT * FROM support_reply_drafts WHERE id = ?", ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public Optional<SupportReplyDraft> findByConfirmationToken(String token) {
        List<SupportReplyDraft> results = jdbcTemplate.query(
                "SELECT * FROM support_reply_drafts WHERE confirmation_token = ?",
                ROW_MAPPER, token);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public boolean markConfirmed(Long id) {
        int rows = jdbcTemplate.update(
                "UPDATE support_reply_drafts SET confirmation_token = NULL WHERE id = ?", id);
        return rows > 0;
    }

    @Override
    public boolean markCanceled(Long id) {
        int rows = jdbcTemplate.update(
                "UPDATE support_reply_drafts SET confirmation_token = NULL WHERE id = ?", id);
        return rows > 0;
    }

    @Override
    public long count() {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_reply_drafts", Long.class);
        return result != null ? result : 0;
    }
}
