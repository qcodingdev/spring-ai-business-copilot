package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** PostgreSQL-backed SQL candidate store with conditional one-time consumption. */
public class JdbcSqlCandidateStore implements SqlCandidateStore {

    private static final RowMapper<SqlCandidate> ROW_MAPPER = (rs, rowNum) -> new SqlCandidate(
            rs.getString("candidate_id"),
            rs.getString("sql_text"),
            null,
            rs.getString("token_digest"),
            SqlCandidateStatus.valueOf(rs.getString("status")),
            rs.getString("owner_actor_id"),
            rs.getString("request_id"),
            rs.getString("model_name"),
            rs.getString("prompt_name"),
            rs.getString("prompt_version"),
            rs.getString("prompt_hash"),
            new AiInvocationMetadata(
                    rs.getString("provider_name"),
                    rs.getString("model_name"),
                    rs.getString("provider_request_id"),
                    (Integer) rs.getObject("input_tokens"),
                    (Integer) rs.getObject("output_tokens"),
                    rs.getString("finish_reason"),
                    rs.getObject("model_latency_ms") != null ? rs.getLong("model_latency_ms") : 0L),
            rs.getString("policy_version"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("consumed_at")),
            rs.getString("action_actor_id"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcSqlCandidateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(SqlCandidate candidate) {
        jdbcTemplate.update("""
                INSERT INTO data_sql_candidates (
                    candidate_id, sql_text, token_digest, status, owner_actor_id, request_id,
                    model_name, prompt_name, prompt_version, prompt_hash,
                    provider_name, provider_request_id, input_tokens, output_tokens,
                    finish_reason, model_latency_ms, policy_version, expires_at,
                    consumed_at, action_actor_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                candidate.candidateId(), candidate.sql(), candidate.tokenDigest(), candidate.status().name(),
                candidate.ownerActorId(), candidate.requestId(), candidate.modelName(), candidate.promptName(),
                candidate.promptVersion(), candidate.promptHash(),
                candidate.aiMetadata() != null ? candidate.aiMetadata().providerName() : null,
                candidate.aiMetadata() != null ? candidate.aiMetadata().providerRequestId() : null,
                candidate.aiMetadata() != null ? candidate.aiMetadata().inputTokens() : null,
                candidate.aiMetadata() != null ? candidate.aiMetadata().outputTokens() : null,
                candidate.aiMetadata() != null ? candidate.aiMetadata().finishReason() : null,
                candidate.aiMetadata() != null ? candidate.aiMetadata().latencyMs() : null,
                candidate.policyVersion(), timestamp(candidate.expiresAt()),
                timestamp(candidate.consumedAt()), candidate.actionActorId(), timestamp(candidate.createdAt()),
                timestamp(candidate.createdAt()));
    }

    @Override
    public SqlCandidate findById(String candidateId) {
        List<SqlCandidate> rows = jdbcTemplate.query("""
                SELECT candidate_id, sql_text, token_digest, status, owner_actor_id, request_id,
                       model_name, prompt_name, prompt_version, prompt_hash,
                       provider_name, provider_request_id, input_tokens, output_tokens,
                       finish_reason, model_latency_ms, policy_version, expires_at,
                       consumed_at, action_actor_id, created_at
                FROM data_sql_candidates
                WHERE candidate_id = ?
                """, ROW_MAPPER, candidateId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public boolean consume(String candidateId, String actionActorId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE data_sql_candidates
                SET status = 'CONSUMED', token_digest = NULL, consumed_at = ?,
                    action_actor_id = ?, updated_at = ?
                WHERE candidate_id = ? AND status = 'PENDING'
                  AND token_digest IS NOT NULL AND expires_at > ?
                """, timestamp(now), actionActorId, timestamp(now), candidateId, timestamp(now)) == 1;
    }

    @Override
    public boolean expire(String candidateId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE data_sql_candidates
                SET status = 'EXPIRED', token_digest = NULL, updated_at = ?
                WHERE candidate_id = ? AND status = 'PENDING'
                """, timestamp(now), candidateId) == 1;
    }

    @Override
    public int evictExpired(Instant now) {
        return jdbcTemplate.update("""
                UPDATE data_sql_candidates
                SET status = 'EXPIRED', token_digest = NULL, updated_at = ?
                WHERE status = 'PENDING' AND expires_at <= ?
                """, timestamp(now), timestamp(now));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
