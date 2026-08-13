package dev.qcoding.businesscopilot.supportcopilot.draft;

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

/** JDBC draft persistence with digest-only tokens and conditional state transitions. */
public class JdbcSupportReplyDraftRepository implements SupportReplyDraftRepository {

    private static final RowMapper<SupportReplyDraft> ROW_MAPPER = (rs, rowNum) -> new SupportReplyDraft(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getString("draft_text"),
            rs.getString("cited_chunk_ids"),
            rs.getString("knowledge_version_ids"),
            dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel.valueOf(
                    rs.getString("risk_level")),
            rs.getString("risk_reasons"),
            null,
            rs.getString("confirmation_token_digest"),
            SupportDraftStatus.valueOf(rs.getString("status")),
            rs.getString("owner_actor_id"),
            rs.getBoolean("review_queue"),
            rs.getString("reviewer_actor_id"),
            rs.getString("action_actor_id"),
            rs.getString("original_draft_text"),
            rs.getString("edited_draft_text"),
            rs.getString("edit_reason"),
            rs.getString("edited_by_actor_id"),
            toInstant(rs.getTimestamp("edited_at")),
            SupportDecisionOutcome.valueOf(rs.getString("decision_outcome")),
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
                    ticket_id, draft_text, cited_chunk_ids, knowledge_version_ids,
                    risk_level, risk_reasons, original_draft_text, decision_outcome,
                    confirmation_token_digest, status, owner_actor_id, review_queue,
                    reviewer_actor_id, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, draft.ticketId());
            ps.setString(2, maskedDraft);
            ps.setString(3, draft.citedChunkIds());
            ps.setString(4, draft.knowledgeVersionIds());
            ps.setString(5, draft.riskLevel().name());
            ps.setString(6, draft.riskReasons());
            ps.setString(7, maskedDraft);
            ps.setString(8, SupportDecisionOutcome.PENDING.name());
            ps.setString(9, draft.tokenDigest());
            ps.setString(10, draft.status().name());
            ps.setString(11, draft.ownerActorId());
            ps.setBoolean(12, draft.reviewQueue());
            ps.setString(13, draft.reviewerActorId());
            ps.setTimestamp(14, Timestamp.from(draft.expiresAt()));
            ps.setTimestamp(15, Timestamp.from(now));
            ps.setTimestamp(16, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return new SupportReplyDraft(
                keyHolder.getKey().longValue(), draft.ticketId(), maskedDraft,
                draft.citedChunkIds(), draft.knowledgeVersionIds(),
                draft.riskLevel(), draft.riskReasons(),
                draft.confirmationToken(), draft.tokenDigest(), draft.status(),
                draft.ownerActorId(), draft.reviewQueue(), draft.reviewerActorId(),
                null, maskedDraft, null, null, null, null,
                SupportDecisionOutcome.PENDING, draft.expiresAt(), now, now);
    }

    @Override
    public Optional<SupportReplyDraft> findById(Long id) {
        List<SupportReplyDraft> rows = jdbcTemplate.query(
                "SELECT * FROM support_reply_drafts WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public boolean transitionStatus(Long id, SupportDraftStatus expectedStatus, SupportDraftStatus targetStatus,
                                    SupportDecisionOutcome outcome, String actionActorId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE support_reply_drafts
                SET status = ?, confirmation_token_digest = NULL,
                    decision_outcome = ?, action_actor_id = ?, updated_at = ?
                WHERE id = ? AND status = ? AND expires_at > ?
                """, targetStatus.name(), outcome.name(), actionActorId, Timestamp.from(now), id,
                expectedStatus.name(), Timestamp.from(now)) == 1;
    }

    @Override
    public boolean edit(Long id, SupportDraftStatus expectedStatus, String editedText,
                        String editReason, String editedByActorId, Instant now) {
        String maskedText = sensitiveTextMasker.mask(editedText);
        return jdbcTemplate.update("""
                UPDATE support_reply_drafts
                SET draft_text = ?, edited_draft_text = ?, edit_reason = ?,
                    edited_by_actor_id = ?, edited_at = ?, updated_at = ?
                WHERE id = ? AND status = ? AND expires_at > ?
                """, maskedText, maskedText, editReason, editedByActorId,
                Timestamp.from(now), Timestamp.from(now), id,
                expectedStatus.name(), Timestamp.from(now)) == 1;
    }

    @Override
    public boolean replaceConfirmationToken(Long id, SupportDraftStatus expectedStatus,
                                            String expectedReviewerActorId, String tokenDigest,
                                            String reviewerActorId, Instant expiresAt, Instant now) {
        return jdbcTemplate.update("""
                UPDATE support_reply_drafts
                SET confirmation_token_digest = ?, reviewer_actor_id = ?,
                    expires_at = ?, updated_at = ?
                WHERE id = ? AND status = ?
                  AND reviewer_actor_id IS NOT DISTINCT FROM ?
                """, tokenDigest, reviewerActorId, Timestamp.from(expiresAt),
                Timestamp.from(now), id, expectedStatus.name(), expectedReviewerActorId) == 1;
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
