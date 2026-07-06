package dev.qcoding.businesscopilot.datacopilot.query;

/**
 * Executes a confirmed, read-only SQL candidate and returns the result as a table.
 *
 * <p>只读查询执行器接口。实现类负责：
 * <ul>
 *   <li>执行前的防御式 guardrails 二次校验；</li>
 *   <li>设置查询超时和最大行数；</li>
 *   <li>对敏感字段脱敏后返回结果。</li>
 * </ul></p>
 */
public interface ReadOnlyQueryExecutor {

    /**
     * Execute a confirmed SQL candidate and return the result table.
     *
     * @param sql the SQL statement retrieved from the confirmed candidate (server-side only)
     * @return the query result with columns, rows, rowCount, and truncated flag
     * @throws QueryExecutionException if execution fails at the database layer
     */
    QueryResultTable execute(String sql);
}
