package dev.qcoding.businesscopilot.evaluation;

import java.util.List;

/**
 * 版本化评测报告（EVAL-04）。
 *
 * <p>记录代码提交、模型标识、Prompt / 策略 / 工具定义 / 数据集版本、环境和预算，
 * 保证不同运行之间的结果可比较、可追溯。缺少凭据、测试跳过、环境未启动、
 * 输出截断等情况标记为 NOT_VERIFIED，不能记为通过（EVAL 门禁规则）。</p>
 */
public record EvaluationReport(
        String codeCommit,
        String modelId,
        String promptVersion,
        String policyVersion,
        String toolVersion,
        String datasetVersion,
        String environment,
        List<CaseResult> results) {

    public EvaluationReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** 单个案例的判定结果。 */
    public record CaseResult(
            String caseId,
            Status status,
            List<AssertionResult> assertionResults,
            List<String> violatedForbiddenBehaviors,
            String failureNote) {

        public CaseResult {
            assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
            violatedForbiddenBehaviors = violatedForbiddenBehaviors == null
                    ? List.of() : List.copyOf(violatedForbiddenBehaviors);
        }
    }

    /** 案例状态：通过、失败、未验证（未验证不算通过）。 */
    public enum Status {
        PASSED,
        FAILED,
        NOT_VERIFIED
    }

    /** 单条断言结果。 */
    public record AssertionResult(String description, String kind, boolean passed, String note) {
    }

    /** 门禁：确定性安全场景必须全部通过；安全违规不能用其他质量分数抵消。 */
    public boolean gatePasses() {
        return !results.isEmpty() && results.stream().allMatch(result ->
                result.status() == Status.PASSED
                        && !result.assertionResults().isEmpty()
                        && result.assertionResults().stream().allMatch(AssertionResult::passed)
                        && result.violatedForbiddenBehaviors().isEmpty());
    }

    /** 汇总文本：样本量、失败案例与适用版本。 */
    public String summary() {
        long passed = results.stream().filter(r -> r.status() == Status.PASSED).count();
        long failed = results.stream().filter(r -> r.status() == Status.FAILED).count();
        long notVerified = results.stream().filter(r -> r.status() == Status.NOT_VERIFIED).count();
        return "评测汇总：样本 %d，通过 %d，失败 %d，未验证 %d；代码提交 %s，模型 %s，数据集版本 %s，环境 %s，门禁 %s"
                .formatted(results.size(), passed, failed, notVerified,
                        codeCommit, modelId, datasetVersion, environment,
                        gatePasses() ? "通过" : "不通过");
    }
}
