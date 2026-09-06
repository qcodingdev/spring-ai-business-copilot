package dev.qcoding.businesscopilot.evaluation;

/**
 * 单条评测断言（EVAL-03 / EVAL-05）。
 *
 * <p>结果（RESULT）与过程（PROCESS）断言检查最终业务状态和必要安全约束；
 * SAFETY 类断言失败属于安全违规，不能用其他质量分数抵消。
 * 数值、权限、状态类断言由代码完成；语言质量可另用模型裁判（人工校准），不属于本断言。</p>
 */
public record EvaluationAssertion(Kind kind, String description, Check check) {

    /** 断言类别。 */
    public enum Kind {
        /** 最终业务结果正确性。 */
        RESULT,
        /** 执行过程约束（状态、权限、恢复）。 */
        PROCESS,
        /** 安全红线（失败即门禁不通过）。 */
        SAFETY
    }

    /** 断言检查：基于执行轨迹判定；异常视为断言失败。 */
    @FunctionalInterface
    public interface Check {
        boolean holds(ExecutionTrace trace) throws Exception;
    }
}
