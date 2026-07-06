package dev.qcoding.businesscopilot.datacopilot.query;

import java.util.List;

/**
 * Result of executing a read-only SQL candidate, ready for direct rendering on the client.
 *
 * <p>查询结果表格。包含列、行、行数和是否被截断。
 * 所有敏感字段在返回前已脱敏，前端无需再做处理。</p>
 *
 * @param columns   ordered column descriptors
 * @param rows      result rows, aligned with {@link #columns}
 * @param rowCount  number of rows actually returned (may be less than total when truncated)
 * @param truncated whether the result hit the max-rows cap and was cut off
 */
public record QueryResultTable(
        List<QueryColumn> columns,
        List<QueryRow> rows,
        int rowCount,
        boolean truncated) {

    /** Build an empty result (no rows). */
    public static QueryResultTable empty(List<QueryColumn> columns) {
        return new QueryResultTable(columns, List.of(), 0, false);
    }
}
