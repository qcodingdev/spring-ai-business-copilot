package dev.qcoding.businesscopilot.datacopilot.explanation;

/**
 * Response from the result explanation service.
 *
 * <p>查询结果解释响应。包含 AI 生成的业务解释文本。
 * 模型调用失败时返回降级解释，degraded=true 标记降级状态。</p>
 *
 * @param explanation concise business-language explanation of the query result
 * @param degraded    true when the AI model failed and a fallback explanation was returned
 */
public record ResultExplanationResponse(
        String explanation,
        boolean degraded) {

    /** Build a successful (non-degraded) explanation. */
    public static ResultExplanationResponse success(String explanation) {
        return new ResultExplanationResponse(explanation, false);
    }

    /** Build a degraded (model-failed) explanation. */
    public static ResultExplanationResponse degraded(String explanation) {
        return new ResultExplanationResponse(explanation, true);
    }
}
