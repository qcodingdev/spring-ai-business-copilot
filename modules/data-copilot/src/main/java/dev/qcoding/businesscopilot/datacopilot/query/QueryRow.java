package dev.qcoding.businesscopilot.datacopilot.query;

import java.util.Map;

/**
 * A single row in a {@link QueryResultTable}, keyed by column name.
 *
 * <p>查询结果行。列名到值的映射，值已通过 {@code SensitiveDataMasker} 脱敏。</p>
 *
 * @param values column name to cell value (already masked if sensitive)
 */
public record QueryRow(Map<String, Object> values) {

    /** Defensive copy so callers cannot mutate the row after construction. */
    public QueryRow {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
