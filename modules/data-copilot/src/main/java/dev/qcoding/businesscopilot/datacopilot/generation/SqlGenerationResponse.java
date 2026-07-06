package dev.qcoding.businesscopilot.datacopilot.generation;

import java.util.List;

/**
 * Response from the SQL generation endpoint.
 *
 * <p>SQL 生成响应。包含 SQL 候选、摘要、假设、警告和校验结果。
 * 校验未通过时仍返回 SQL 和违规原因，前端据此禁用执行按钮。</p>
 *
 * @param requestId     request identifier for tracing and audit
 * @param question      echoed user question
 * @param sql           generated SQL (or empty if generation failed)
 * @param summary       concise description of the query
 * @param assumptions   assumptions made by the model
 * @param warnings      warnings raised by the model
 * @param validation    guardrails validation summary
 * @param executable    whether the candidate may proceed to execution
 */
public record SqlGenerationResponse(
        String requestId,
        String question,
        String sql,
        String summary,
        List<String> assumptions,
        List<String> warnings,
        SqlCandidateValidationSummary validation,
        boolean executable) {
}
