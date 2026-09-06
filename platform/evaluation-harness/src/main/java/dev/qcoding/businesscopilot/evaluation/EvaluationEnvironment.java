package dev.qcoding.businesscopilot.evaluation;

import java.util.List;
import java.util.Optional;

/**
 * 评测执行环境契约（EVAL-02）。
 *
 * <p>由各模块或应用测试提供实现：初始化测试数据、固定时钟与模型响应、
 * 注入可控故障。实现必须保证回放模式只在隔离测试环境中执行。</p>
 */
public interface EvaluationEnvironment {

    /** 缺少凭据、未启动环境或未执行案例；不能作为通过结果。 */
    class UnavailableException extends RuntimeException {
        public UnavailableException() {
            super("Evaluation prerequisites unavailable");
        }
    }

    /** 写入案例初始数据。 */
    default void prepare(EvaluationCase evaluationCase) {
    }

    /** 案例要求的故障序列；空表示正常路径。 */
    default List<FaultScenario> faults() {
        return List.of();
    }

    /** 固定时钟（可空：使用真实时钟）。 */
    default Optional<java.time.Instant> fixedNow() {
        return Optional.empty();
    }

    /** 固定模型响应（按调用序号）；实现可按 operation 路由。 */
    default Optional<String> scriptedModelResponse(int callIndex) {
        return Optional.empty();
    }

    /** 只清理本次创建的测试资源，不删除已有跨项目资源。 */
    default void cleanup(EvaluationCase evaluationCase) {
    }
}
