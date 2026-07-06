package dev.qcoding.businesscopilot.guardrails;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for SQL guardrails and sensitive-field policies.
 *
 * <p>SQL 安全校验和敏感字段策略配置。默认值遵循第一版安全红线。</p>
 *
 * @param queryableTables    tables the Data Copilot is allowed to query
 * @param blockedColumns     high-sensitivity columns that may never be selected directly
 * @param maskedColumns      columns that may be queried but masked in the result
 * @param defaultMaxRows     maximum number of rows a single query may return
 * @param requireLimit       whether non-aggregate queries must include an explicit LIMIT
 */
@ConfigurationProperties(prefix = "business-copilot.guardrails")
public record GuardrailsProperties(
        List<String> queryableTables,
        List<String> blockedColumns,
        List<String> maskedColumns,
        int defaultMaxRows,
        boolean requireLimit) {

    /** Conservative defaults aligned with the v1 security boundary. */
    public GuardrailsProperties {
        if (queryableTables == null || queryableTables.isEmpty()) {
            // 默认业务白名单表，审计表 query_audit_logs 不在其中
            queryableTables = List.of(
                    "customers", "products", "orders", "order_items",
                    "refunds", "marketing_events");
        }
        if (blockedColumns == null || blockedColumns.isEmpty()) {
            // 高敏字段直接阻断，禁止直接查询
            blockedColumns = List.of("password", "token", "secret", "id_card");
        }
        if (maskedColumns == null || maskedColumns.isEmpty()) {
            // 中敏字段允许查询但返回前脱敏
            maskedColumns = List.of("phone", "email");
        }
        if (defaultMaxRows <= 0) {
            defaultMaxRows = 100;
        }
    }
}
