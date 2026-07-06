package dev.qcoding.businesscopilot.datacopilot.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuration for Data Copilot schema and queryable tables.
 *
 * <p>Data Copilot schema 配置。queryableTables 定义白名单，
 * query_audit_logs 不在其中，因此不会被自然语言查询暴露。
 * 字段描述和敏感标记通过 columnDescriptions 和 sensitiveColumns 配置。</p>
 *
 * @param queryableTables     tables allowed for natural language queries
 * @param columnDescriptions per-column business descriptions keyed by "tableName.columnName"
 * @param sensitiveColumns   columns marked as sensitive, keyed by "tableName.columnName"
 * @param maxSchemaTextLength soft cap on schema prompt text length
 */
@ConfigurationProperties(prefix = "business-copilot.data-copilot.schema")
public record DataCopilotSchemaProperties(
        List<String> queryableTables,
        Map<String, String> columnDescriptions,
        Map<String, String> sensitiveColumns,
        int maxSchemaTextLength) {

    /** Defaults aligned with v1 security boundary. */
    public DataCopilotSchemaProperties {
        if (queryableTables == null || queryableTables.isEmpty()) {
            // 默认白名单，审计表 query_audit_logs 不在其中
            queryableTables = List.of(
                    "customers", "products", "orders", "order_items",
                    "refunds", "marketing_events");
        }
        if (columnDescriptions == null) {
            columnDescriptions = Map.of();
        }
        if (sensitiveColumns == null) {
            // 默认敏感标记：高敏字段和脱敏字段
            sensitiveColumns = Map.of(
                    "customers.password", "block",
                    "customers.token", "block",
                    "customers.secret", "block",
                    "customers.id_card", "block",
                    "customers.phone", "mask",
                    "customers.email", "mask");
        }
        if (maxSchemaTextLength <= 0) {
            maxSchemaTextLength = 2000;
        }
    }
}
