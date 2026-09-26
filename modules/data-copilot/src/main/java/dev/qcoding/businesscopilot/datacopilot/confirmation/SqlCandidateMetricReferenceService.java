package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** Persists and revalidates the approved metric versions used to generate a SQL candidate. */
public class SqlCandidateMetricReferenceService {

    private final JdbcTemplate jdbcTemplate;

    public SqlCandidateMetricReferenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String candidateId, List<MetricReference> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        for (MetricReference reference : references) {
            jdbcTemplate.update("""
                    INSERT INTO data_candidate_metric_refs (candidate_id, metric_key, metric_version)
                    VALUES (?, ?, ?)
                    ON CONFLICT (candidate_id, metric_key) DO UPDATE
                    SET metric_version = EXCLUDED.metric_version
                    """, candidateId, reference.metricKey(), reference.version());
        }
    }

    /**
     * Locks every adopted metric row until the candidate is consumed. This closes the race between
     * revalidation and a concurrent metric update/deactivation.
     */
    public void requireCurrent(String candidateId) {
        List<MetricReference> references = jdbcTemplate.query("""
                SELECT metric_key, metric_version
                FROM data_candidate_metric_refs
                WHERE candidate_id = ?
                ORDER BY metric_key
                """, (rs, rowNum) -> new MetricReference(
                rs.getString("metric_key"), rs.getInt("metric_version")), candidateId);
        for (MetricReference reference : references) {
            List<Integer> activeVersions = jdbcTemplate.queryForList("""
                    SELECT version
                    FROM data_metric_definitions
                    WHERE metric_key = ? AND active = TRUE AND approved_by IS NOT NULL
                    FOR UPDATE
                    """, Integer.class, reference.metricKey());
            if (activeVersions.size() != 1 || activeVersions.getFirst() != reference.version()) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT);
            }
        }
    }

    public record MetricReference(String metricKey, int version) {
    }
}
