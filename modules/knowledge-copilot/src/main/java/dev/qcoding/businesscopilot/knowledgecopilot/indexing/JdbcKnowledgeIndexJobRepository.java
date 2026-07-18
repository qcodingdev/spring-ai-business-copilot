package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 使用 SKIP LOCKED 保证多工作线程安全领取任务的 PostgreSQL 队列实现。 */
public class JdbcKnowledgeIndexJobRepository implements KnowledgeIndexJobRepository {

    private static final RowMapper<KnowledgeIndexJob> ROW_MAPPER = (rs, rowNum) ->
            new KnowledgeIndexJob(
                    rs.getLong("id"),
                    rs.getLong("document_id"),
                    KnowledgeIndexJobStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getString("embedding_model"),
                    (Integer) rs.getObject("embedding_dim"),
                    (Integer) rs.getObject("chunk_count"),
                    rs.getString("error_category"),
                    instant(rs.getTimestamp("next_attempt_at")),
                    instant(rs.getTimestamp("started_at")),
                    instant(rs.getTimestamp("finished_at")),
                    instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("updated_at")));

    private static final String SELECT_COLUMNS = """
            id, document_id, status, attempts, embedding_model, embedding_dim,
            chunk_count, error_category, next_attempt_at, started_at, finished_at,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeIndexJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KnowledgeIndexJob enqueue(Long documentId) {
        Instant now = Instant.now();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_index_jobs (
                    document_id, status, attempts, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'PENDING', 0, ?, ?, ?)
                RETURNING id
                """, Long.class, documentId, timestamp(now), timestamp(now), timestamp(now));
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<KnowledgeIndexJob> findById(Long id) {
        List<KnowledgeIndexJob> jobs = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM knowledge_index_jobs WHERE id = ?",
                ROW_MAPPER, id);
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.getFirst());
    }

    @Override
    public Optional<KnowledgeIndexJob> claimNext(Instant now) {
        List<KnowledgeIndexJob> jobs = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                    FROM knowledge_index_jobs
                    WHERE status IN ('PENDING', 'RETRYABLE')
                      AND next_attempt_at <= ?
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE knowledge_index_jobs job
                SET status = 'PROCESSING',
                    attempts = attempts + 1,
                    started_at = ?,
                    updated_at = ?
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.document_id, job.status, job.attempts,
                          job.embedding_model, job.embedding_dim, job.chunk_count,
                          job.error_category, job.next_attempt_at, job.started_at,
                          job.finished_at, job.created_at, job.updated_at
                """, ROW_MAPPER, timestamp(now), timestamp(now), timestamp(now));
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.getFirst());
    }

    @Override
    public void complete(Long id, String model, int dimension, int chunkCount, Instant now) {
        jdbcTemplate.update("""
                UPDATE knowledge_index_jobs
                SET status = 'COMPLETED', embedding_model = ?, embedding_dim = ?,
                    chunk_count = ?, error_category = NULL, finished_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, model, dimension, chunkCount, timestamp(now), timestamp(now), id);
    }

    @Override
    public void retry(Long id, String errorCategory, Instant nextAttemptAt, Instant now) {
        jdbcTemplate.update("""
                UPDATE knowledge_index_jobs
                SET status = 'RETRYABLE', error_category = ?, next_attempt_at = ?,
                    finished_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, errorCategory, timestamp(nextAttemptAt), timestamp(now), timestamp(now), id);
    }

    @Override
    public void fail(Long id, String errorCategory, Instant now) {
        jdbcTemplate.update("""
                UPDATE knowledge_index_jobs
                SET status = 'FAILED', error_category = ?, finished_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, errorCategory, timestamp(now), timestamp(now), id);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
