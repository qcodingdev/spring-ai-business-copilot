package dev.qcoding.businesscopilot.guardrails;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for SQL guardrails and sensitive-field policies.
 *
 * <p>SQL 安全校验和敏感字段策略配置。默认值遵循第一版安全红线。</p>
 *
 * @param queryableTables    tables the Data Copilot is allowed to query
 * @param queryableColumns   fully qualified columns the Data Copilot is allowed to reference
 * @param blockedColumns     high-sensitivity columns that may never be selected directly
 * @param maskedColumns      columns that may be queried but masked in the result
 * @param defaultMaxRows     maximum number of rows a single query may return
 * @param requireLimit       whether non-aggregate queries must include an explicit LIMIT
 * @param allowedAggregateFunctions aggregate functions that may be used in generated SQL
 */
@ConfigurationProperties(prefix = "business-copilot.guardrails")
public record GuardrailsProperties(
        List<String> queryableTables,
        List<String> queryableColumns,
        List<String> blockedColumns,
        List<String> maskedColumns,
        int defaultMaxRows,
        boolean requireLimit,
        List<String> allowedAggregateFunctions) {

    public GuardrailsProperties(List<String> queryableTables,
                                List<String> blockedColumns,
                                List<String> maskedColumns,
                                int defaultMaxRows,
                                boolean requireLimit) {
        this(queryableTables, null, blockedColumns, maskedColumns,
                defaultMaxRows, requireLimit, null);
    }

    public GuardrailsProperties(List<String> queryableTables,
                                List<String> blockedColumns,
                                List<String> maskedColumns,
                                int defaultMaxRows,
                                boolean requireLimit,
                                List<String> allowedAggregateFunctions) {
        this(queryableTables, null, blockedColumns, maskedColumns,
                defaultMaxRows, requireLimit, allowedAggregateFunctions);
    }

    /** Conservative defaults aligned with the v1 security boundary. */
    public GuardrailsProperties {
        if (queryableTables == null || queryableTables.isEmpty()) {
            // 默认业务白名单表，审计表 query_audit_logs 不在其中
            queryableTables = List.of(
                    "public.customers", "public.products", "public.orders",
                    "public.order_items", "public.refunds", "public.marketing_events");
        }
        if (queryableColumns == null || queryableColumns.isEmpty()) {
            queryableColumns = List.of(
                    "public.customers.id", "public.customers.name",
                    "public.customers.email", "public.customers.phone",
                    "public.customers.created_at",
                    "public.products.id", "public.products.name",
                    "public.products.category", "public.products.price",
                    "public.products.stock", "public.products.created_at",
                    "public.orders.id", "public.orders.customer_id",
                    "public.orders.status", "public.orders.total_amount",
                    "public.orders.created_at",
                    "public.order_items.id", "public.order_items.order_id",
                    "public.order_items.product_id", "public.order_items.quantity",
                    "public.order_items.unit_price", "public.order_items.subtotal",
                    "public.refunds.id", "public.refunds.order_id",
                    "public.refunds.amount", "public.refunds.status",
                    "public.refunds.created_at",
                    "public.marketing_events.id", "public.marketing_events.name",
                    "public.marketing_events.channel", "public.marketing_events.start_date",
                    "public.marketing_events.end_date", "public.marketing_events.budget");
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
        if (allowedAggregateFunctions == null || allowedAggregateFunctions.isEmpty()) {
            allowedAggregateFunctions = List.of("count", "sum", "avg", "min", "max");
        }
    }
}
