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

/** JDBC draft persistence with digest-only tokens and conditional state transitions. */
public class JdbcSupportReplyDraftRepository implements SupportReplyDraftRepository {

    private static final RowMapper<SupportReplyDraft> ROW_MAPPER = (rs, rowNum) -> new SupportReplyDraft(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getString("draft_text"),
            rs.getString("cited_chunk_ids"),
            rs.getString("risk_level"),
            rs.getString("risk_reasons"),
            null,
            rs.getString("confirmation_token_digest"),
            rs.getString("status"),
            rs.getString("owner_actor_id"),
            rs.getBoolean("review_queue"),
            rs.getString("reviewer_actor_id"),
            rs.getString("action_actor_id"),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveTextMasker sensitiveTextMasker;

    public JdbcSupportReplyDraftRepository(JdbcTemplate jdbcTemplate, SensitiveTextMasker sensitiveTextMasker) {
        this.jdbcTemplate = jdbcTemplate;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    @Override
    public SupportReplyDraft save(SupportReplyDraft draft) {
        String maskedDraft = sensitiveTextMasker.mask(draft.draftText());
        String sql = """
                INSERT INTO support_reply_drafts (
                    ticket_id, draft_text, cited_chunk_ids, risk_level, risk_reasons,
                    confirmation_token_digest, status, owner_actor_id, review_queue,
                    reviewer_actor_id, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, draft.ticketId());
            ps.setString(2, maskedDraft);
            ps.setString(3, draft.citedChunkIds());
            ps.setString(4, draft.riskLevel());
            ps.setString(5, draft.riskReasons());
            ps.setString(6, draft.tokenDigest());
            ps.setString(7, draft.status());
            ps.setString(8, draft.ownerActorId());
            ps.setBoolean(9, draft.reviewQueue());
            ps.setString(10, draft.reviewerActorId());
            ps.setTimestamp(11, Timestamp.from(draft.expiresAt()));
            ps.setTimestamp(12, Timestamp.from(now));
            ps.setTimestamp(13, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return new SupportReplyDraft(
                keyHolder.getKey().longValue(), draft.ticketId(), maskedDraft,
                draft.citedChunkIds(), draft.riskLevel(), draft.riskReasons(),
                draft.confirmationToken(), draft.tokenDigest(), draft.status(),
                draft.ownerActorId(), draft.reviewQueue(), draft.reviewerActorId(),
                null, draft.expiresAt(), now, now);
    }

    @Override
    public Optional<SupportReplyDraft> findById(Long id) {
        List<SupportReplyDraft> rows = jdbcTemplate.query(
                "SELECT * FROM support_reply_drafts WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public boolean transitionStatus(Long id, String expectedStatus, String targetStatus,
                                    String actionActorId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE support_reply_drafts
                SET status = ?, confirmation_token_digest = NULL,
                    action_actor_id = ?, updated_at = ?
                WHERE id = ? AND status = ? AND expires_at > ?
                """, targetStatus, actionActorId, Timestamp.from(now), id,
                expectedStatus, Timestamp.from(now)) == 1;
    }

    @Override
    public long count() {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_reply_drafts", Long.class);
        return result == null ? 0 : result;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
