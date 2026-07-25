package dev.qcoding.businesscopilot.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 场景目录 JDBC 仓储；服务端范围和资源引用只保存在数据库。 */
@Repository
public class DemoScenarioRepository {

    private final JdbcTemplate jdbcTemplate;

    public DemoScenarioRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DemoScenario> findEnabled(DemoModule module) {
        return jdbcTemplate.query("""
                SELECT scenario_id, module, title, description, input_template, allowed_operations,
                       data_scope_json, data_scope_label, version, enabled, system_managed,
                       fallback_result_available, content_hash
                FROM demo_scenarios
                WHERE enabled = TRUE AND (? IS NULL OR module = ?)
                ORDER BY module, scenario_id
                """, this::map, module == null ? null : module.name(), module == null ? null : module.name());
    }

    public Optional<DemoScenario> findById(String scenarioId) {
        List<DemoScenario> scenarios = jdbcTemplate.query("""
                SELECT scenario_id, module, title, description, input_template, allowed_operations,
                       data_scope_json, data_scope_label, version, enabled, system_managed,
                       fallback_result_available, content_hash
                FROM demo_scenarios
                WHERE scenario_id = ?
                """, this::map, scenarioId);
        return scenarios.isEmpty() ? Optional.empty() : Optional.of(scenarios.getFirst());
    }

    public void upsert(DemoScenario scenario) {
        jdbcTemplate.update("""
                INSERT INTO demo_scenarios (
                    scenario_id, module, title, description, input_template, allowed_operations,
                    data_scope_json, data_scope_label, version, enabled, system_managed,
                    fallback_result_available, content_hash, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scenario_id) DO UPDATE SET
                    module = EXCLUDED.module,
                    title = EXCLUDED.title,
                    description = EXCLUDED.description,
                    input_template = EXCLUDED.input_template,
                    allowed_operations = EXCLUDED.allowed_operations,
                    data_scope_json = EXCLUDED.data_scope_json,
                    data_scope_label = EXCLUDED.data_scope_label,
                    version = EXCLUDED.version,
                    enabled = EXCLUDED.enabled,
                    system_managed = EXCLUDED.system_managed,
                    fallback_result_available = EXCLUDED.fallback_result_available,
                    content_hash = EXCLUDED.content_hash,
                    updated_at = EXCLUDED.updated_at
                """,
                scenario.scenarioId(), scenario.module().name(), scenario.title(), scenario.description(),
                scenario.inputTemplate(), encodeOperations(scenario.allowedOperations()),
                scenario.dataScopeJson(), scenario.dataScopeLabel(), scenario.version(), scenario.enabled(),
                scenario.systemManaged(), scenario.fallbackResultAvailable(), scenario.contentHash(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    public void upsertSampleResult(String scenarioId, int scenarioVersion, String resultJson,
                                   Instant generatedAt, String contentHash) {
        jdbcTemplate.update("""
                INSERT INTO demo_scenario_results (
                    scenario_id, scenario_version, result_json, generated_at, reviewed_at,
                    content_hash, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scenario_id) DO UPDATE SET
                    scenario_version = EXCLUDED.scenario_version,
                    result_json = EXCLUDED.result_json,
                    generated_at = EXCLUDED.generated_at,
                    reviewed_at = EXCLUDED.reviewed_at,
                    content_hash = EXCLUDED.content_hash,
                    updated_at = EXCLUDED.updated_at
                """, scenarioId, scenarioVersion, resultJson, Timestamp.from(generatedAt),
                Timestamp.from(Instant.now()), contentHash,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    public Optional<SampleResultRecord> findSampleResult(String scenarioId) {
        List<SampleResultRecord> records = jdbcTemplate.query("""
                SELECT r.scenario_id, r.scenario_version, r.result_json, r.generated_at
                FROM demo_scenario_results r
                JOIN demo_scenarios s ON s.scenario_id = r.scenario_id
                WHERE r.scenario_id = ?
                  AND s.enabled = TRUE
                  AND s.fallback_result_available = TRUE
                  AND r.scenario_version = s.version
                """, (rs, rowNum) -> new SampleResultRecord(
                rs.getString("scenario_id"),
                rs.getInt("scenario_version"),
                rs.getString("result_json"),
                rs.getTimestamp("generated_at").toInstant()), scenarioId);
        return records.isEmpty() ? Optional.empty() : Optional.of(records.getFirst());
    }

    public long countEnabled() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_scenarios WHERE enabled = TRUE", Long.class);
        return count == null ? 0L : count;
    }

    private DemoScenario map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DemoScenario(
                rs.getString("scenario_id"),
                DemoModule.valueOf(rs.getString("module")),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("input_template"),
                decodeOperations(rs.getString("allowed_operations")),
                rs.getString("data_scope_json"),
                rs.getString("data_scope_label"),
                rs.getInt("version"),
                rs.getBoolean("enabled"),
                rs.getBoolean("system_managed"),
                rs.getBoolean("fallback_result_available"),
                rs.getString("content_hash"));
    }

    private String encodeOperations(List<DemoOperation> operations) {
        return operations.stream().map(Enum::name).reduce((left, right) -> left + "," + right).orElse("");
    }

    private List<DemoOperation> decodeOperations(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        return Arrays.stream(encoded.split(",")).map(String::trim).filter(value -> !value.isBlank())
                .map(DemoOperation::valueOf).toList();
    }

    public record SampleResultRecord(
            String scenarioId, int scenarioVersion, String resultJson, Instant generatedAt) {
    }
}
