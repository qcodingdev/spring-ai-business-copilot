package dev.qcoding.businesscopilot.datacopilot.schema;

import java.util.List;

/**
 * Table-level metadata within the Data Copilot schema context.
 *
 * @param name       table name
 * @param columns    column schemas for this table
 * @param description business description for prompt context
 */
public record TableSchema(
        String name,
        List<ColumnSchema> columns,
        String description) {
}
