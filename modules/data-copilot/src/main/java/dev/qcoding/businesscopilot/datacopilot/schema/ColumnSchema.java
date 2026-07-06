package dev.qcoding.businesscopilot.datacopilot.schema;

import java.util.List;

/**
 * Column-level metadata within a table schema.
 *
 * @param name           column name
 * @param type           data type (e.g. varchar, integer)
 * @param nullable       whether the column allows null values
 * @param description    business description for prompt context
 * @param sensitive      whether this column holds sensitive data
 * @param maskingStrategy masking strategy if sensitive (null if not sensitive)
 */
public record ColumnSchema(
        String name,
        String type,
        boolean nullable,
        String description,
        boolean sensitive,
        String maskingStrategy) {
}
