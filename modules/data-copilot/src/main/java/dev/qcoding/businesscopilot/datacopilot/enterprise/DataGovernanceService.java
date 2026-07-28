package dev.qcoding.businesscopilot.datacopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidate;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.schema.BusinessDatabaseDialect;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContext;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data Copilot 企业治理能力：指标、批准模板、健康、schema 漂移和成本预检。 */
public class DataGovernanceService {

    private static final Pattern POSTGRES_ESTIMATED_ROWS =
            Pattern.compile("\\\\?\"Plan Rows\\\\?\"\\s*:\\s*(\\d+)");
    private static final Pattern MYSQL_ESTIMATED_ROWS =
            Pattern.compile("\\\\?\"rows(?:_examined_per_scan)?\\\\?\"\\s*:\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate platformJdbcTemplate;
    private final JdbcTemplate businessJdbcTemplate;
    private final BusinessDatabaseDialect dialect;
    private final SchemaContextService schemaContextService;
    private final SqlGuardrailService guardrailService;
    private final GuardrailsProperties guardrailsProperties;
    private final SqlConfirmationService confirmationService;
    private final CurrentActorProvider actorProvider;
    private final ObjectMapper objectMapper;
    private final DataEnterpriseProperties enterpriseProperties;

    public DataGovernanceService(
            JdbcTemplate platformJdbcTemplate,
            JdbcTemplate businessJdbcTemplate,
            BusinessDatabaseDialect dialect,
            SchemaContextService schemaContextService,
            SqlGuardrailService guardrailService,
            GuardrailsProperties guardrailsProperties,
            SqlConfirmationService confirmationService,
            CurrentActorProvider actorProvider,
            ObjectMapper objectMapper,
            DataEnterpriseProperties enterpriseProperties) {
        this.platformJdbcTemplate = platformJdbcTemplate;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.dialect = dialect;
        this.schemaContextService = schemaContextService;
        this.guardrailService = guardrailService;
        this.guardrailsProperties = guardrailsProperties;
        this.confirmationService = confirmationService;
        this.actorProvider = actorProvider;
        this.objectMapper = objectMapper;
        this.enterpriseProperties = enterpriseProperties;
    }

    public MetricDefinition saveMetric(MetricCommand command) {
        validateSql(command.expressionSql());
        String actorId = actorProvider.currentActor().actorId();
        return platformJdbcTemplate.queryForObject("""
                INSERT INTO data_metric_definitions (
                    metric_key, display_name, description, unit, expression_sql, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (metric_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    description = EXCLUDED.description,
                    unit = EXCLUDED.unit,
                    expression_sql = EXCLUDED.expression_sql,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    active = FALSE,
                    approved_by = NULL,
                    approved_at = NULL,
                    version = data_metric_definitions.version + 1,
                    updated_at = now()
                RETURNING id, metric_key, display_name, description, unit, expression_sql,
                          active, version, approved_by, updated_at
                """, this::mapMetric,
                command.metricKey().trim(), command.displayName().trim(),
                command.description().trim(), trimToNull(command.unit()),
                command.expressionSql().trim(), actorId);
    }

    public MetricDefinition approveMetric(long id) {
        String actorId = actorProvider.currentActor().actorId();
        List<MetricDefinition> rows = platformJdbcTemplate.query("""
                UPDATE data_metric_definitions
                SET active = TRUE, approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ?
                RETURNING id, metric_key, display_name, description, unit, expression_sql,
                          active, version, approved_by, updated_at
                """, this::mapMetric, actorId, id);
        return firstOrNotFound(rows);
    }

    public List<MetricDefinition> metrics() {
        return platformJdbcTemplate.query("""
                SELECT id, metric_key, display_name, description, unit, expression_sql,
                       active, version, approved_by, updated_at
                FROM data_metric_definitions
                ORDER BY display_name, version DESC
                """, this::mapMetric);
    }

    public QueryTemplate saveTemplate(TemplateCommand command) {
        validateSql(command.sql());
        String actorId = actorProvider.currentActor().actorId();
        Long nextVersion = platformJdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM data_query_templates WHERE template_key = ?
                """, Long.class, command.templateKey().trim());
        return platformJdbcTemplate.queryForObject("""
                INSERT INTO data_query_templates (
                    template_key, name, description, sql_text, owner_actor_id, version
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, template_key, name, description, sql_text, active,
                          version, approved_by, updated_at
                """, this::mapTemplate, command.templateKey().trim(), command.name().trim(),
                command.description().trim(), command.sql().trim(), actorId, nextVersion);
    }

    public QueryTemplate approveTemplate(long id) {
        String actorId = actorProvider.currentActor().actorId();
        List<QueryTemplate> rows = platformJdbcTemplate.query("""
                UPDATE data_query_templates
                SET active = TRUE, approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ?
                RETURNING id, template_key, name, description, sql_text, active,
                          version, approved_by, updated_at
                """, this::mapTemplate, actorId, id);
        return firstOrNotFound(rows);
    }

    public List<QueryTemplate> templates() {
        return platformJdbcTemplate.query("""
                SELECT id, template_key, name, description, sql_text, active,
                       version, approved_by, updated_at
                FROM data_query_templates
                ORDER BY template_key, version DESC
                """, this::mapTemplate);
    }

    public TemplateLaunch launchTemplate(long id) {
        List<String> sql = platformJdbcTemplate.query("""
                SELECT sql_text FROM data_query_templates WHERE id = ? AND active = TRUE
                """, (rs, rowNum) -> rs.getString(1), id);
        if (sql.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        validateSql(sql.getFirst());
        SqlCandidate candidate = confirmationService.createExecutableCandidate(
                sql.getFirst(), "approved-template:" + id, null, null, null,
                SqlGuardrailService.POLICY_VERSION);
        return new TemplateLaunch(candidate.candidateId(), candidate.confirmationToken(), candidate.expiresAt());
    }

    public DataSourceHealth health() {
        long start = System.nanoTime();
        try {
            Integer value = businessJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new DataSourceHealth(value != null && value == 1, dialect.name(), latencyMs, null);
        } catch (RuntimeException ex) {
            return new DataSourceHealth(false, dialect.name(),
                    (System.nanoTime() - start) / 1_000_000, "CONNECTION_FAILED");
        }
    }

    public SchemaChangeCheck checkSchema() {
        SchemaContext current = schemaContextService.buildContext();
        String schemaJson = json(current.tables());
        String hash = sha256(schemaJson);
        List<PreviousSnapshot> previous = platformJdbcTemplate.query("""
                SELECT schema_hash, schema_json::text
                FROM data_schema_snapshots
                WHERE source_name = 'business-query'
                ORDER BY checked_at DESC LIMIT 1
                """, (rs, rowNum) -> new PreviousSnapshot(
                rs.getString("schema_hash"), rs.getString("schema_json")));
        boolean changed = !previous.isEmpty() && !previous.getFirst().hash().equals(hash);
        List<String> summary = previous.isEmpty()
                ? List.of("已建立首个 schema 基线")
                : changed ? List.of("白名单 schema 或字段元数据发生变化") : List.of();
        platformJdbcTemplate.update("""
                INSERT INTO data_schema_snapshots (
                    source_name, schema_hash, schema_json, change_summary, checked_by, changed
                ) VALUES ('business-query', ?, ?::jsonb, ?::jsonb, ?, ?)
                """, hash, schemaJson, json(summary), actorProvider.currentActor().actorId(), changed);
        return new SchemaChangeCheck(hash, changed, summary, Instant.now());
    }

    public CostPreview previewCost(String sql) {
        validateSql(sql);
        String explainSql = dialect == BusinessDatabaseDialect.MYSQL
                ? "EXPLAIN FORMAT=JSON " + sql : "EXPLAIN (FORMAT JSON) " + sql;
        try {
            List<java.util.Map<String, Object>> rows = businessJdbcTemplate.queryForList(explainSql);
            String plan = json(rows);
            String normalized = plan.toLowerCase(java.util.Locale.ROOT);
            String risk = normalized.contains("seq scan") || normalized.contains("table scan")
                    ? "HIGH" : plan.length() > 20_000 ? "MEDIUM" : "LOW";
            long estimatedRows = maxEstimatedRows(plan);
            boolean withinRowBudget = estimatedRows <= enterpriseProperties.maxEstimatedRows();
            boolean highRiskBlocked = enterpriseProperties.blockHighRiskPlan() && "HIGH".equals(risk);
            boolean allowed = withinRowBudget && !highRiskBlocked;
            String rejectionReason = !withinRowBudget
                    ? "QUERY_BUDGET_EXCEEDED"
                    : highRiskBlocked ? "HIGH_RISK_PLAN" : null;
            return new CostPreview(risk, estimatedRows,
                    enterpriseProperties.maxEstimatedRows(), allowed,
                    rejectionReason,
                    Math.min(plan.length(), 20_000),
                    plan.substring(0, Math.min(plan.length(), 20_000)));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.QUERY_EXECUTION_ERROR,
                    "数据库未能返回安全的查询成本计划");
        }
    }

    private long maxEstimatedRows(String plan) {
        Pattern pattern = dialect == BusinessDatabaseDialect.MYSQL
                ? MYSQL_ESTIMATED_ROWS : POSTGRES_ESTIMATED_ROWS;
        Matcher matcher = pattern.matcher(plan);
        long max = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            try {
                max = Math.max(max, Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return Long.MAX_VALUE;
            }
        }
        // 无法识别计划行数时拒绝执行，避免解析器或数据库版本差异绕过预算。
        return found ? max : Long.MAX_VALUE;
    }

    private void validateSql(String sql) {
        var validation = guardrailService.validate(sql, guardrailsProperties);
        if (!validation.passed()) {
            throw new BusinessException(ErrorCode.SQL_GUARDRAIL_VIOLATION,
                    "SQL 未通过只读和白名单校验");
        }
    }

    private MetricDefinition mapMetric(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MetricDefinition(rs.getLong("id"), rs.getString("metric_key"),
                rs.getString("display_name"), rs.getString("description"), rs.getString("unit"),
                rs.getString("expression_sql"), rs.getBoolean("active"), rs.getLong("version"),
                rs.getString("approved_by"), rs.getTimestamp("updated_at").toInstant());
    }

    private QueryTemplate mapTemplate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new QueryTemplate(rs.getLong("id"), rs.getString("template_key"), rs.getString("name"),
                rs.getString("description"), rs.getString("sql_text"), rs.getBoolean("active"),
                rs.getLong("version"), rs.getString("approved_by"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private <T> T firstOrNotFound(List<T> values) {
        if (values.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return values.getFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("企业数据治理对象序列化失败", ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PreviousSnapshot(String hash, String json) { }
    public record MetricCommand(String metricKey, String displayName, String description,
                                String unit, String expressionSql) { }
    public record MetricDefinition(long id, String metricKey, String displayName, String description,
                                   String unit, String expressionSql, boolean active, long version,
                                   String approvedBy, Instant updatedAt) { }
    public record TemplateCommand(String templateKey, String name, String description, String sql) { }
    public record QueryTemplate(long id, String templateKey, String name, String description,
                                String sql, boolean active, long version, String approvedBy,
                                Instant updatedAt) { }
    public record TemplateLaunch(String candidateId, String confirmationToken, Instant expiresAt) { }
    public record DataSourceHealth(boolean healthy, String dialect, long latencyMs, String errorCategory) { }
    public record SchemaChangeCheck(String schemaHash, boolean changed, List<String> changes,
                                    Instant checkedAt) { }
    public record CostPreview(String riskLevel, long estimatedRows, long rowBudget,
                              boolean allowed, String rejectionReason,
                              int planBytes, String boundedPlan) { }
}
