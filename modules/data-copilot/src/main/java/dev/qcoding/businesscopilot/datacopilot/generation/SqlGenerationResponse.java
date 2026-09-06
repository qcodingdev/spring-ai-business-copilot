package dev.qcoding.businesscopilot.datacopilot.generation;

import java.time.Instant;
import java.util.List;

/**
 * Response from the SQL generation endpoint.
 *
 * <p>SQL 生成响应。包含 SQL 候选、摘要、假设、警告和校验结果。
 * 校验未通过时仍返回 SQL 和违规原因，前端据此禁用执行按钮。
 * guardrails 通过时返回 candidateId、confirmationToken、expiresAt，
 * 前端执行时只能传 candidateId + confirmationToken，不能传回 SQL。</p>
 *
 * <p>DATA-01/02：adoptedMetrics 记录本次生成采用的已审批指标及版本；
 * clarificationQuestions 非空表示关键业务口径不足，需要先澄清而不是生成可执行候选。</p>
 *
 * @param requestId              request identifier for tracing and audit
 * @param question               echoed user question
 * @param sql                    generated SQL (or empty if generation failed)
 * @param summary                concise description of the query
 * @param assumptions            assumptions made by the model
 * @param warnings               warnings raised by the model
 * @param validation             guardrails validation summary
 * @param executable             whether the candidate may proceed to execution
 * @param candidateId            candidate identifier (only present when executable)
 * @param confirmationToken      secure confirmation token (only present when executable)
 * @param expiresAt              when this candidate expires (only present when executable)
 * @param adoptedMetrics         approved metric versions adopted as calibers (DATA-01)
 * @param clarificationQuestions questions to clarify before generation (DATA-02)
 */
public record SqlGenerationResponse(
        String requestId,
        String question,
        String sql,
        String summary,
        List<String> assumptions,
        List<String> warnings,
        SqlCandidateValidationSummary validation,
        boolean executable,
        String candidateId,
        String confirmationToken,
        Instant expiresAt,
        List<AdoptedMetric> adoptedMetrics,
        List<String> clarificationQuestions,
        String reviewStatus) {

    public SqlGenerationResponse {
        adoptedMetrics = adoptedMetrics == null ? List.of() : List.copyOf(adoptedMetrics);
        clarificationQuestions = clarificationQuestions == null
                ? List.of() : List.copyOf(clarificationQuestions);
    }

    /** Build a non-executable response (guardrails failed) — no candidateId/token/expiresAt. */
    public static SqlGenerationResponse notExecutable(
            String requestId,
            String question,
            String sql,
            String summary,
            List<String> assumptions,
            List<String> warnings,
            SqlCandidateValidationSummary validation) {
        return new SqlGenerationResponse(
                requestId, question, sql, summary, assumptions, warnings, validation,
                false, null, null, null, List.of(), List.of(), null);
    }

    /** DATA-02：口径不足时的澄清响应；不生成候选，不调用模型。 */
    public static SqlGenerationResponse clarification(
            String requestId, String question, List<String> clarificationQuestions) {
        return new SqlGenerationResponse(
                requestId, question, null, null, List.of(), List.of(), null,
                false, null, null, null, List.of(), clarificationQuestions, null);
    }

    /** DATA-01：本次生成采用的已审批指标版本。 */
    public record AdoptedMetric(String metricKey, String displayName, int version) {
    }
}
