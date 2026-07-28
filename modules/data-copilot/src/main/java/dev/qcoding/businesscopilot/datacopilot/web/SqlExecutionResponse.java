package dev.qcoding.businesscopilot.datacopilot.web;

import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationResponse;
import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;

/**
 * Response from the SQL execution endpoint: the result table plus an AI explanation.
 *
 * <p>SQL 执行响应。包含脱敏后的查询结果表格和 AI 生成的业务解释。
 * 执行失败时不会到达此结构（会抛 BusinessException 由全局异常处理器处理）。</p>
 *
 * @param table       masked query result table
 * @param explanation AI-generated business explanation (may be degraded)
 */
public record SqlExecutionResponse(
        QueryResultTable table,
        ResultExplanationResponse explanation,
        Long resultId,
        String executionId) {

    public SqlExecutionResponse(QueryResultTable table, ResultExplanationResponse explanation) {
        this(table, explanation, null, null);
    }
}
