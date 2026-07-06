package dev.qcoding.businesscopilot.datacopilot.explanation;

import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;

/**
 * Request to generate an AI explanation for an executed query result.
 *
 * <p>查询结果解释请求。包含用户问题、已执行 SQL 和脱敏后的查询结果表格。
 * 表格中的敏感字段在执行阶段已经脱敏，这里不会再次接触原始敏感值。</p>
 *
 * @param question the user's original natural-language question
 * @param sql      the SQL statement that was executed
 * @param result   the masked query result table (columns, rows, rowCount, truncated)
 */
public record ResultExplanationRequest(
        String question,
        String sql,
        QueryResultTable result) {
}
