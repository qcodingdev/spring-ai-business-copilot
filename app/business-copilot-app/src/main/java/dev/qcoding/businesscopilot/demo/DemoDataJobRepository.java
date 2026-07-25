package dev.qcoding.businesscopilot.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Demo 数据任务状态仓储。 */
@Repository
public class DemoDataJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public DemoDataJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DemoDataJob create(DemoDataJob.JobType type, String actorId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO demo_data_jobs (
                    id, job_type, status, requested_by, created_at, updated_at
                ) VALUES (?, ?, 'PENDING', ?, ?, ?)
                """, id, type.name(), actorId, Timestamp.from(now), Timestamp.from(now));
        return new DemoDataJob(id, type, DemoDataJob.JobStatus.PENDING, actorId,
                null, null, now, null, null);
    }

    public Optional<DemoDataJob> find(UUID id) {
        List<DemoDataJob> jobs = jdbcTemplate.query("""
                SELECT id, job_type, status, requested_by, summary_json, error_category,
                       created_at, started_at, finished_at
                FROM demo_data_jobs WHERE id = ?
                """, (rs, rowNum) -> new DemoDataJob(
                rs.getObject("id", UUID.class),
                DemoDataJob.JobType.valueOf(rs.getString("job_type")),
                DemoDataJob.JobStatus.valueOf(rs.getString("status")),
                rs.getString("requested_by"),
                rs.getString("summary_json"),
                rs.getString("error_category"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at"))), id);
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.getFirst());
    }

    public void running(UUID id) {
        jdbcTemplate.update("""
                UPDATE demo_data_jobs SET status = 'RUNNING', started_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
    }

    public void completed(UUID id, String summaryJson) {
        jdbcTemplate.update("""
                UPDATE demo_data_jobs
                SET status = 'COMPLETED', summary_json = ?, finished_at = ?, updated_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """, summaryJson, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
    }

    public void failed(UUID id, String errorCategory) {
        jdbcTemplate.update("""
                UPDATE demo_data_jobs
                SET status = 'FAILED', error_category = ?, finished_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                """, errorCategory, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
