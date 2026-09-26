package dev.qcoding.businesscopilot.evaluation;

/**
 * 案例执行器：在给定环境中执行一个案例并返回执行轨迹。
 *
 * <p>执行器由业务模块测试提供（驱动真实的服务、状态机与权限逻辑，
 * 模型响应由环境脚本固定）。执行器不得自行判定通过与否——判定统一由断言完成。</p>
 */
@FunctionalInterface
public interface ScenarioExecutor {

    ExecutionTrace execute(EvaluationCase evaluationCase, EvaluationEnvironment environment)
            throws Exception;
}
