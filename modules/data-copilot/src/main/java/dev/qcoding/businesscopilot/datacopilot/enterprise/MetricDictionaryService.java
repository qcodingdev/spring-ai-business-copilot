package dev.qcoding.businesscopilot.datacopilot.enterprise;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

/**
 * DATA-01：已审批指标口径的只读访问。
 *
 * <p>自由问答生成前检索已审批（active=TRUE 且 approved_by 非空）的指标定义并注入生成上下文；
 * 记录采用的版本。停用或未审批的定义一律不进入生成依据，
 * 保证执行口径只能来自人工审批过的版本。</p>
 */
public class MetricDictionaryService {

    private final JdbcTemplate jdbcTemplate;

    public MetricDictionaryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 全部已审批且启用的指标定义（按 key 排序，保证 prompt 稳定）。 */
    public List<ApprovedMetric> approvedMetrics() {
        return jdbcTemplate.query("""
                SELECT id, metric_key, display_name, description, unit, expression_sql, version
                FROM data_metric_definitions
                WHERE active = TRUE AND approved_by IS NOT NULL
                ORDER BY metric_key, version DESC
                """, (rs, rowNum) -> new ApprovedMetric(
                rs.getLong("id"),
                rs.getString("metric_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("unit"),
                rs.getString("expression_sql"),
                rs.getInt("version")));
    }

    /** 与问题词面相关的已审批指标（key/名称/描述的包含匹配，确定性、不调模型）。 */
    public List<ApprovedMetric> matchingMetrics(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return approvedMetrics().stream()
                .filter(metric -> containsAny(normalized, metric.metricKey(), metric.displayName(),
                        metric.description()))
                .toList();
    }

    private boolean containsAny(String normalizedQuestion, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isBlank()
                    && normalizedQuestion.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 渲染进 prompt 的指标上下文；无审批指标时给固定占位，保证模板稳定。 */
    public String renderPromptContext(List<ApprovedMetric> metrics) {
        if (metrics.isEmpty()) {
            return "（当前没有已审批指标定义；只能依据 schema 白名单和问题本身确定口径，"
                    + "不得假设业务口径。）";
        }
        StringBuilder sb = new StringBuilder();
        for (ApprovedMetric metric : metrics) {
            sb.append("- ").append(metric.metricKey())
                    .append("（").append(metric.displayName()).append("）")
                    .append(" v").append(metric.version());
            if (metric.unit() != null && !metric.unit().isBlank()) {
                sb.append("，单位：").append(metric.unit());
            }
            if (metric.description() != null && !metric.description().isBlank()) {
                sb.append("，口径：").append(metric.description());
            }
            if (metric.expressionSql() != null && !metric.expressionSql().isBlank()) {
                sb.append("，参考 SQL：").append(metric.expressionSql());
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public record ApprovedMetric(long id, String metricKey, String displayName, String description,
                                 String unit, String expressionSql, int version) {

        public String versionTag() {
            return metricKey + ":v" + version;
        }
    }
}
