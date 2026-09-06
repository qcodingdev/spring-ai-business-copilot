package dev.qcoding.businesscopilot.evaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务级评测执行器（Evaluation Harness）。
 *
 * <p>准备环境 → 执行案例 → 分别检查过程与结果断言 → 输出版本化报告。
 * Harness 是独立测试基础设施，不进入生产业务调用链。
 * 安全断言失败即案例失败；案例执行异常记为 FAILED（未验证语义由调用方标记）。</p>
 */
public class EvaluationHarness {

    private final ScenarioExecutor executor;
    private final ReportContext context;

    /** 版本上下文（EVAL-04）：报告据此可追溯。 */
    public record ReportContext(String codeCommit, String modelId, String promptVersion,
                                String policyVersion, String toolVersion, String datasetVersion,
                                String environment) {
    }

    public EvaluationHarness(ScenarioExecutor executor, ReportContext context) {
        this.executor = executor;
        this.context = context;
    }

    /** 运行一批案例并生成报告；环境准备与清理只作用于本次创建的测试资源。 */
    public EvaluationReport run(List<EvaluationCase> cases, EvaluationEnvironment environment) {
        List<EvaluationReport.CaseResult> results = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            results.add(runOne(evaluationCase, environment));
        }
        return new EvaluationReport(context.codeCommit(), context.modelId(),
                context.promptVersion(), context.policyVersion(), context.toolVersion(),
                context.datasetVersion(), context.environment(), results);
    }

    private EvaluationReport.CaseResult runOne(EvaluationCase evaluationCase,
                                               EvaluationEnvironment environment) {
        EvaluationReport.CaseResult result;
        boolean prepared = false;
        try {
            environment.prepare(evaluationCase);
            prepared = true;
            ExecutionTrace trace = executor.execute(evaluationCase, environment);
            result = trace == null
                    ? unavailable(evaluationCase, "执行器未返回轨迹")
                    : evaluate(evaluationCase, trace);
        } catch (EvaluationEnvironment.UnavailableException ex) {
            result = unavailable(evaluationCase, "评测条件不满足");
        } catch (Exception | AssertionError ex) {
            result = new EvaluationReport.CaseResult(evaluationCase.caseId(),
                    prepared ? EvaluationReport.Status.FAILED : EvaluationReport.Status.NOT_VERIFIED,
                    List.of(), List.of(), (prepared ? "案例执行异常：" : "环境准备失败：")
                    + ex.getClass().getSimpleName());
        }
        // 即使准备只完成了一部分也必须清理；清理失败必须阻断门禁，并保留原判定证据。
        try {
            environment.cleanup(evaluationCase);
        } catch (Exception | AssertionError ex) {
            return new EvaluationReport.CaseResult(evaluationCase.caseId(),
                    EvaluationReport.Status.FAILED, result.assertionResults(),
                    result.violatedForbiddenBehaviors(),
                    (result.failureNote() == null ? "" : result.failureNote() + "；")
                            + "资源清理失败：" + ex.getClass().getSimpleName());
        }
        return result;
    }

    private EvaluationReport.CaseResult unavailable(EvaluationCase evaluationCase, String note) {
        return new EvaluationReport.CaseResult(evaluationCase.caseId(),
                EvaluationReport.Status.NOT_VERIFIED, List.of(), List.of(), note);
    }

    private EvaluationReport.CaseResult evaluate(EvaluationCase evaluationCase, ExecutionTrace trace) {
        if (evaluationCase.assertions().isEmpty()) {
            return unavailable(evaluationCase, "未配置可执行断言");
        }
        List<EvaluationReport.AssertionResult> assertionResults = new ArrayList<>();
        List<String> violated = new ArrayList<>();
        boolean allPassed = true;
        for (EvaluationAssertion assertion : evaluationCase.assertions()) {
            boolean passed;
            String note = null;
            try {
                passed = assertion.check().holds(trace);
            } catch (Exception | AssertionError ex) {
                passed = false;
                note = ex.getClass().getSimpleName();
            }
            if (!passed) {
                allPassed = false;
            }
            assertionResults.add(new EvaluationReport.AssertionResult(
                    assertion.description(), assertion.kind().name(), passed, note));
        }
        if (evaluationCase.maxModelCalls() != null) {
            boolean withinBudget = trace.count("model-call", null) <= evaluationCase.maxModelCalls();
            assertionResults.add(new EvaluationReport.AssertionResult("模型调用不超过案例预算",
                    EvaluationAssertion.Kind.SAFETY.name(), withinBudget, null));
            allPassed &= withinBudget;
        }
        // 禁止行为由执行器在轨迹中标注 "forbidden:<行为>"；出现即视为违规
        for (String behavior : evaluationCase.forbiddenBehaviors()) {
            if (trace.performed("forbidden", behavior)) {
                violated.add(behavior);
                allPassed = false;
            }
        }
        return new EvaluationReport.CaseResult(evaluationCase.caseId(),
                allPassed ? EvaluationReport.Status.PASSED : EvaluationReport.Status.FAILED,
                assertionResults, violated, null);
    }
}
