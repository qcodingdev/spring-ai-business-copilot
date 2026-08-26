package dev.qcoding.businesscopilot.readiness;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only persistence for delivery evidence snapshots. */
@Repository
public class EnterpriseReadinessSnapshotRepository {

    private static final TypeReference<List<EnterpriseReadiness.Check>> CHECK_LIST_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<EnterpriseReadiness.Snapshot> rowMapper = (rs, rowNum) ->
            new EnterpriseReadiness.Snapshot(
                    rs.getLong("id"),
                    rs.getObject("snapshot_reference", UUID.class),
                    rs.getInt("schema_version"),
                    rs.getString("purpose"),
                    rs.getString("application_version"),
                    rs.getString("runtime_mode"),
                    EnterpriseReadiness.OverallStatus.valueOf(rs.getString("status")),
                    rs.getInt("passed_count"),
                    rs.getInt("warning_count"),
                    rs.getInt("blocker_count"),
                    readChecks(rs.getString("checks_json")),
                    rs.getString("content_hash"),
                    rs.getString("generated_by"),
                    rs.getTimestamp("generated_at").toInstant(),
                    rs.getTimestamp("valid_until").toInstant());

    public EnterpriseReadinessSnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public EnterpriseReadiness.Snapshot save(EnterpriseReadiness.SnapshotDraft draft) {
        EnterpriseReadiness.Assessment assessment = draft.assessment();
        return jdbcTemplate.queryForObject("""
                INSERT INTO enterprise_readiness_snapshots (
                    snapshot_reference, schema_version, purpose, application_version,
                    runtime_mode, status, passed_count, warning_count, blocker_count,
                    checks_json, content_hash, generated_by, generated_at, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                RETURNING id, snapshot_reference, schema_version, purpose, application_version,
                          runtime_mode, status, passed_count, warning_count, blocker_count,
                          checks_json::text, content_hash, generated_by, generated_at, valid_until
                """, rowMapper,
                draft.snapshotReference(), assessment.schemaVersion(), draft.purpose(),
                assessment.applicationVersion(), assessment.runtimeMode(), assessment.status().name(),
                assessment.passedCount(), assessment.warningCount(), assessment.blockerCount(),
                writeChecks(assessment.checks()), assessment.contentHash(), draft.generatedBy(),
                Timestamp.from(assessment.generatedAt()), Timestamp.from(assessment.validUntil()));
    }

    public List<EnterpriseReadiness.Snapshot> findAll(int page, int size) {
        return jdbcTemplate.query("""
                SELECT id, snapshot_reference, schema_version, purpose, application_version,
                       runtime_mode, status, passed_count, warning_count, blocker_count,
                       checks_json::text, content_hash, generated_by, generated_at, valid_until
                FROM enterprise_readiness_snapshots
                ORDER BY generated_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, rowMapper, size, Math.multiplyExact((long) page, size));
    }

    public long count() {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enterprise_readiness_snapshots", Long.class);
        return total == null ? 0L : total;
    }

    public int deleteGeneratedBefore(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM enterprise_readiness_snapshots WHERE generated_at <= ?",
                Timestamp.from(cutoff));
    }

    private String writeChecks(List<EnterpriseReadiness.Check> checks) {
        try {
            return objectMapper.writeValueAsString(checks);
        } catch (JacksonException ex) {
            throw new IllegalStateException("企业运行就绪检查序列化失败", ex);
        }
    }

    private List<EnterpriseReadiness.Check> readChecks(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, CHECK_LIST_TYPE));
        } catch (JacksonException ex) {
            throw new IllegalStateException("企业运行就绪快照读取失败", ex);
        }
    }
}
