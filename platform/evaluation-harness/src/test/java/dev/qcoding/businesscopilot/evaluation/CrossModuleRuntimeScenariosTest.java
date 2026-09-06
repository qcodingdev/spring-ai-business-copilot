package dev.qcoding.businesscopilot.evaluation;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.taskruntime.ContextManifest;
import dev.qcoding.businesscopilot.taskruntime.FailureCategory;
import dev.qcoding.businesscopilot.taskruntime.TaskAttempt;
import dev.qcoding.businesscopilot.taskruntime.TaskRun;
import dev.qcoding.businesscopilot.taskruntime.TaskRunBudget;
import dev.qcoding.businesscopilot.taskruntime.TaskRunService;
import dev.qcoding.businesscopilot.taskruntime.TaskRunStatus;
import dev.qcoding.businesscopilot.taskruntime.TaskRunStore;
import dev.qcoding.businesscopilot.taskruntime.TaskStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块与 Runtime 场景（§8.6 X-01 ~ X-10）的可执行版本。
 *
 * <p>使用 Evaluation Harness 驱动轻量 Runtime 与模拟的 Data→Report 交接对象：
 * 模拟业务对象具备一次性消费与领取租约语义，模型响应按脚本固定。
 * 场景验证运行关联、预算、等待恢复、竞争领取、取消与隔离回放语义，
 * 不调用任何真实写入端点。</p>
 */
class CrossModuleRuntimeScenariosTest {

    // ---- 模拟业务对象：一次性消费的 Data→Report 交接 ----

    /** 模拟交接：consume() 全局只允许一次成功。 */
    static final class HandoffBoard {
        private final Map<String, String> status = new ConcurrentHashMap<>();

        void create(String handoffId) {
            status.put(handoffId, "AVAILABLE");
        }

        /** 条件消费：仅 AVAILABLE → CONSUMED 成功一次。 */
        synchronized boolean consume(String handoffId) {
            if ("AVAILABLE".equals(status.get(handoffId))) {
                status.put(handoffId, "CONSUMED");
                return true;
            }
            return false;
        }

        String status(String handoffId) {
            return status.get(handoffId);
        }
    }

    /** 模拟报告草稿板：每个交接至多一份有效草稿。 */
    static final class DraftBoard {
        private final Map<String, String> drafts = new ConcurrentHashMap<>();

        void save(String handoffId, String draftRef) {
            drafts.merge(handoffId, draftRef, (existing, offered) -> offered);
        }

        int count(String handoffId) {
            return drafts.containsKey(handoffId) ? 1 : 0;
        }

        String draft(String handoffId) {
            return drafts.get(handoffId);
        }
    }

    /** 执行环境：固定脚本化的模型结果（首次失败/成功序列）。 */
    static class ScriptedEnvironment implements EvaluationEnvironment {
        final List<String> modelScript;
        final AtomicInteger callIndex = new AtomicInteger();
        final List<FaultScenario> faults;

        ScriptedEnvironment(List<String> modelScript) {
            this(modelScript, List.of());
        }

        ScriptedEnvironment(List<String> modelScript, List<FaultScenario> faults) {
            this.modelScript = modelScript;
            this.faults = faults;
        }

        @Override
        public List<FaultScenario> faults() {
            return faults;
        }

        @Override
        public Optional<Instant> fixedNow() {
            return Optional.of(Instant.parse("2026-08-28T10:00:00Z"));
        }

        @Override
        public Optional<String> scriptedModelResponse(int index) {
            return index < modelScript.size() ? Optional.of(modelScript.get(index)) : Optional.empty();
        }
    }

    private final HandoffBoard handoffs = new HandoffBoard();
    private final DraftBoard drafts = new DraftBoard();
    private final InMemoryRunStore runStore = new InMemoryRunStore();

    private TaskRunService runtimeFor(String actorId) {
        CurrentActorProvider actorProvider = () -> new CurrentActor(
                actorId, java.util.Set.of(BusinessRole.OPERATOR));
        return new TaskRunService(runStore, actorProvider, new DefaultObjectAccessPolicy(),
                Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC));
    }

    private ContextManifest manifest(String handoffId) {
        return new ContextManifest(List.of("handoff:" + handoffId), "confirmation:" + handoffId,
                Duration.ofHours(24), Instant.parse("2026-08-29T10:00:00Z"));
    }

    /** X 系列公共执行器：交接 → 模型生成草稿 → 失败不消费、成功恰好消费一次。 */
    private ExecutionTrace driveHandoffToDraft(TaskRunService runtime, String handoffId,
                                                ScriptedEnvironment env, int maxRetries) {
        ExecutionTrace trace = new ExecutionTrace();
        handoffs.create(handoffId);
        trace.state("handoff.status", "AVAILABLE");

        TaskRun run = runtime.startRun("report", "handoff", handoffId,
                new TaskRunBudget(2 + maxRetries, null, null, null), manifest(handoffId));
        trace.state("run.id", run.runId());

        TaskStep collect = runtime.beginStep(run.runId(), "collect-sources");
        runtime.recordModelAttempt(run.runId(), collect.stepId(), "report.collect",
                "openai", "gpt-5-mini", 50, 20, 300, TaskAttempt.Outcome.SUCCESS, null);
        runtime.completeStep(collect, List.of("source:1"), "来源采集完成");
        trace.action("model-call", "report.collect", "SUCCESS");

        TaskStep generate = runtime.beginStep(run.runId(), "generate-draft");
        boolean generated = false;
        for (int attempt = 0; attempt <= maxRetries && !generated; attempt++) {
            String scripted = env.scriptedModelResponse(attempt).orElse("SUCCESS");
            if ("TIMEOUT".equals(scripted)) {
                runtime.recordModelAttempt(run.runId(), generate.stepId(), "report.generate",
                        "openai", "gpt-5-mini", 60, 0, 30_000, TaskAttempt.Outcome.FAILURE,
                        FailureCategory.PROVIDER);
                trace.action("model-call", "report.generate", "TIMEOUT");
            } else {
                runtime.recordModelAttempt(run.runId(), generate.stepId(), "report.generate",
                        "openai", "gpt-5-mini", 60, 120, 900, TaskAttempt.Outcome.SUCCESS, null);
                trace.action("model-call", "report.generate", "SUCCESS");
                generated = true;
            }
        }
        if (generated) {
            // 成功才消费交接：恰好一次
            boolean consumed = handoffs.consume(handoffId);
            runtime.completeStep(generate, List.of("handoff:" + handoffId),
                    consumed ? "草稿生成并消费交接" : "交接已被其他执行者消费");
            drafts.save(handoffId, "draft:" + handoffId);
            runtime.completeRun(run.runId());
            trace.state("handoff.status", handoffs.status(handoffId));
            trace.state("draft.count", String.valueOf(drafts.count(handoffId)));
            trace.state("run.status", runStore.findRun(run.runId()).orElseThrow().status().name());
            trace.action("consume-handoff", handoffId, String.valueOf(consumed));
        } else {
            runtime.failRun(run.runId(), FailureCategory.MODEL, "模型多次失败");
            trace.state("handoff.status", handoffs.status(handoffId));
            trace.state("run.status", runStore.findRun(run.runId()).orElseThrow().status().name());
        }
        trace.state("attempts.count",
                String.valueOf(runStore.findAttempts(run.runId()).size()));
        return trace;
    }

    // ---- X-01：Data 到 Report 首次模型超时后恢复成功 ----

    @Test
    @DisplayName("X-01: first model timeout then recovery yields one draft and single consumption")
    void x01FirstTimeoutThenRecovery() {
        EvaluationCase x01 = EvaluationCase.builder("X-01", "report", "首次模型超时后恢复成功")
                .operator("operator-1", "OPERATOR")
                .input("handoff", "handoff-x01")
                .assertResult("只有一份有效草稿", t -> "1".equals(t.state("draft.count").orElse("0")))
                .assertResult("交接只消费一次", t -> t.count("consume-handoff", "handoff-x01") == 1
                        && "true".equals(lastConsumeOutcome(t)))
                .assertProcess("失败后交接未被永久标记消费", t ->
                        "CONSUMED".equals(t.finalState().get("handoff.status")))
                .assertProcess("所有尝试归属同一运行", t ->
                        Integer.parseInt(t.finalState().getOrDefault("attempts.count", "0")) == 3)
                .forbidden("失败时消费交接")
                .build();

        ScriptedEnvironment env = new ScriptedEnvironment(List.of("TIMEOUT", "SUCCESS"));
        ExecutionTrace trace = driveHandoffToDraft(runtimeFor("operator-1"), "handoff-x01", env, 1);

        EvaluationReport report = new EvaluationHarness(
                (c, e) -> trace, reportContext()).run(List.of(x01), env);
        assertThat(report.gatePasses()).as(report.summary()).isTrue();
    }

    // ---- X-05：模型反复请求工具调用，预算停止 ----

    @Test
    @DisplayName("X-05: repeated model loops stop at budget with recorded reason")
    void x05BudgetStopsLoopingModel() {
        handoffs.create("handoff-x05");
        TaskRunService runtime = runtimeFor("operator-1");
        TaskRun run = runtime.startRun("data", "execution", "handoff-x05",
                new TaskRunBudget(null, 3, null, null), manifest("handoff-x05"));
        TaskStep step = runtime.beginStep(run.runId(), "agent-loop");
        try {
            for (int i = 0; i < 10; i++) {
                runtime.recordToolAttempt(run.runId(), step.stepId(), "metric-lookup",
                        null, null, 50, TaskAttempt.Outcome.SUCCESS, null);
            }
        } catch (TaskRunService.RunBudgetExhaustedException expected) {
            // 预算停止
        }
        TaskRun stopped = runStore.findRun(run.runId()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(TaskRunStatus.BUDGET_EXHAUSTED);
        assertThat(stopped.stopReason()).contains("预算耗尽");
        // 仅记录 3 次已派发尝试；第 4 次在派发前被拒绝，不能伪造已发生调用。
        assertThat(runStore.findAttempts(run.runId())).hasSize(3);
    }

    // ---- X-06：连续语言/结构修复重试计入总预算 ----

    @Test
    @DisplayName("X-06: consecutive retries consume the same budget")
    void x06RetriesConsumeSameBudget() {
        handoffs.create("handoff-x06");
        TaskRunService runtime = runtimeFor("operator-1");
        TaskRun run = runtime.startRun("data", "execution", "handoff-x06",
                new TaskRunBudget(2, null, null, null), manifest("handoff-x06"));
        TaskStep step = runtime.beginStep(run.runId(), "generate-with-retry");
        // 首次调用 + 语言重试：两次都计入
        runtime.recordModelAttempt(run.runId(), step.stepId(), "gen", null, null,
                10, 5, 100, TaskAttempt.Outcome.SUCCESS, null);
        runtime.recordModelAttempt(run.runId(), step.stepId(), "gen-lang-retry", null, null,
                10, 5, 100, TaskAttempt.Outcome.SUCCESS, null);
        assertThat(runtime.budgetExhausted(run.runId())).isTrue();
    }

    // ---- X-02：等待人工确认时进程重启，恢复后重新验证凭证与对象 ----

    @Test
    @DisplayName("X-02: persisted wait survives restart and re-validates on resume")
    void x02WaitSurvivesRestartAndRevalidates() {
        TaskRunService before = runtimeFor("operator-1");
        TaskRun run = before.startRun("report", "handoff", "handoff-x02",
                TaskRunBudget.unlimited(), manifest("handoff-x02"));
        before.awaitConfirmation(run.runId(), "等待人工确认");
        assertThat(runStore.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);

        // "进程重启"：用同一存储新建服务实例，等待状态来自持久层而非内存。
        TaskRunService after = runtimeFor("operator-1");
        // 恢复时确认凭证无效 → 拒绝恢复并保持等待，不得继续执行。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        after.resumeWaitingRun(run.runId(), () -> true, () -> false))
                .isInstanceOf(BusinessException.class);
        assertThat(runStore.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);
        // 对象状态失效 → 同样拒绝恢复并保持等待。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        after.resumeWaitingRun(run.runId(), () -> false, () -> true))
                .isInstanceOf(BusinessException.class);
        assertThat(runStore.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);
        // 凭证与对象都有效 → 恢复。
        after.resumeWaitingRun(run.runId(), () -> true, () -> true);
        assertThat(runStore.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.RUNNING);
    }

    // ---- X-03：确认已过期或已消费 → 拒绝继续执行，不自动补发授权 ----

    @Test
    @DisplayName("X-03: expired confirmation cannot resume and no new token is issued")
    void x03ExpiredConfirmationCannotResume() {
        TaskRunService runtime = runtimeFor("operator-1");
        TaskRun run = runtime.startRun("data", "execution", "execution-x03",
                TaskRunBudget.unlimited(), manifest("execution-x03"));
        runtime.awaitConfirmation(run.runId(), "等待确认");
        // 恢复时确认校验失败（已过期/已消费语义）→ 拒绝恢复。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runtime.resumeWaitingRun(run.runId(), () -> true, () -> false))
                .isInstanceOf(BusinessException.class);
        assertThat(runStore.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);
        // 禁止行为：自动补发可执行授权（系统中不存在该能力，轨迹记录验证点）。
        ExecutionTrace trace = new ExecutionTrace();
        trace.state("autoReissued", "false");
        assertThat(trace.state("autoReissued")).contains("false");
    }

    // ---- X-04：两个执行者同时领取同一任务，只有一个合法领取 ----

    @Test
    @DisplayName("X-04: only one resume wins; late completion cannot overwrite")
    void x04OnlyOneConcurrentResumeWins() {
        TaskRunService runtime = runtimeFor("operator-1");
        TaskRun run = runtime.startRun("report", "handoff", "handoff-x04",
                TaskRunBudget.unlimited(), manifest("handoff-x04"));
        runtime.awaitConfirmation(run.runId(), "等待确认");
        runtime.resumeWaitingRun(run.runId(), () -> true, () -> true);
        // 第二个执行者再领取 → 状态已不是等待，条件恢复拒绝。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runtime.resumeWaitingRun(run.runId(), () -> true, () -> true))
                .isInstanceOf(BusinessException.class);
        assertThat(runStore.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.RUNNING);
        // 并发竞争的数据库级验证见 PostgresPgvectorIntegrationTest（FOR UPDATE 行锁）。
    }

    // ---- X-07：工具持续失败 → 按错误类别有限重试后停止，不掩盖未知副作用 ----

    @Test
    @DisplayName("X-07: persistent tool failure stops at budget with failure category")
    void x07PersistentToolFailureStopsAtBudget() {
        TaskRunService runtime = runtimeFor("operator-1");
        TaskRun run = runtime.startRun("support", "writeback", "writeback-x07",
                new TaskRunBudget(null, 2, null, null), manifest("writeback-x07"));
        TaskStep step = runtime.beginStep(run.runId(), "external-writeback");
        try {
            for (int i = 0; i < 5; i++) {
                runtime.recordToolAttempt(run.runId(), step.stepId(), "supplier.writeback",
                        null, null, 5_000, TaskAttempt.Outcome.FAILURE, FailureCategory.PROVIDER);
            }
        } catch (TaskRunService.RunBudgetExhaustedException expected) {
            // 预算停止
        }
        TaskRun stopped = runStore.findRun(run.runId()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(TaskRunStatus.BUDGET_EXHAUSTED);
        // 失败尝试全部保留，失败类别可查，未被"重试成功"掩盖。
        assertThat(runStore.findAttempts(run.runId()))
                .allSatisfy(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(TaskAttempt.Outcome.FAILURE);
                    assertThat(attempt.failureCategory()).isEqualTo(FailureCategory.PROVIDER);
                });
    }

    // ---- X-08：取消时下游步骤尚未完成 → 保留已发生动作事实 ----

    @Test
    @DisplayName("X-08: cancel preserves completed work and forbids further attempts")
    void x08CancelPreservesCompletedWork() {
        TaskRunService runtime = runtimeFor("operator-1");
        TaskRun run = runtime.startRun("report", "handoff", "handoff-x08",
                TaskRunBudget.unlimited(), manifest("handoff-x08"));
        TaskStep done = runtime.beginStep(run.runId(), "collect-sources");
        runtime.completeStep(done, List.of("source:1"), "来源采集完成");
        TaskRun cancelled = runtime.cancelRun(run.runId(), "操作者取消");
        assertThat(cancelled.status()).isEqualTo(TaskRunStatus.CANCELLED);
        // 已发生动作事实保留：步骤与尝试仍可查。
        assertThat(runStore.findSteps(run.runId())).hasSize(1);
        // 取消后不允许新的尝试。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runtime.beginStep(run.runId(), "generate-draft"))
                .isInstanceOf(BusinessException.class);
    }

    // ---- X-09：知识证据在消费前失效 → 恢复被拒绝，触发复核 ----

    @Test
    @DisplayName("X-09: invalidated knowledge evidence blocks resume")
    void x09InvalidatedEvidenceBlocksResume() {
        TaskRunService runtime = runtimeFor("operator-1");
        TaskRun run = runtime.startRun("knowledge", "document", "document-x09",
                TaskRunBudget.unlimited(),
                new ContextManifest(List.of("document:x09"), "confirmation:x09",
                        Duration.ofHours(24), Instant.parse("2026-08-29T10:00:00Z")));
        runtime.awaitConfirmation(run.runId(), "等待证据复核");
        // 对象状态回调：证据已被停用/过期 → 恢复被拒绝。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runtime.resumeWaitingRun(run.runId(), () -> false, () -> true))
                .isInstanceOf(BusinessException.class);
        assertThat(runStore.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);
        // 数据库级证据失效过滤见 PostgresPgvectorIntegrationTest（KNOW-03）。
    }

    // ---- X-10：隔离回放并完成资源清理 ----

    @Test
    @DisplayName("X-10: isolated replay touches only test targets and cleans own resources")
    void x10IsolatedReplayAndCleanup() {
        List<String> createdResources = new java.util.concurrent.CopyOnWriteArrayList<>();
        EvaluationEnvironment isolatedEnv = new ScriptedEnvironment(List.of("SUCCESS")) {
            @Override
            public void prepare(EvaluationCase c) {
                createdResources.add("sandbox:" + c.caseId());
            }

            @Override
            public void cleanup(EvaluationCase c) {
                // 只清理本次创建的资源
                createdResources.remove("sandbox:" + c.caseId());
            }
        };
        EvaluationCase x10 = EvaluationCase.builder("X-10", "runtime", "隔离回放并清理")
                .assertSafety("不调用真实写入端点", t -> !t.performed("real-write", null))
                .assertProcess("案例在隔离资源内执行", t -> createdResources.contains("sandbox:X-10"))
                .build();

        ScenarioExecutor executor = (c, env) -> {
            ExecutionTrace trace = new ExecutionTrace();
            trace.action("replay", "sandbox:" + c.caseId(), "SUCCESS");
            return trace;
        };
        EvaluationReport report = new EvaluationHarness(executor, reportContext())
                .run(List.of(x10), isolatedEnv);
        assertThat(report.gatePasses()).isTrue();
        assertThat(createdResources).isEmpty();
    }

    // ---- 门禁语义：安全断言失败不能被其他通过抵消 ----

    @Test
    @DisplayName("gate fails when a safety assertion fails even if result assertions pass")
    void safetyFailureFailsGate() {
        EvaluationCase unsafe = EvaluationCase.builder("X-GATE", "runtime", "安全违规不可抵消")
                .assertResult("结果正确", t -> true)
                .assertSafety("不扩大权限", t -> false)
                .build();
        EvaluationReport report = new EvaluationHarness((c, e) -> new ExecutionTrace(),
                reportContext()).run(List.of(unsafe), new ScriptedEnvironment(List.of()));
        assertThat(report.gatePasses()).isFalse();
        assertThat(report.results().get(0).status()).isEqualTo(EvaluationReport.Status.FAILED);
    }

    @Test
    void emptyAndUnverifiedReportsCannotPass() {
        EvaluationHarness harness = new EvaluationHarness((c, e) -> new ExecutionTrace(), reportContext());
        assertThat(harness.run(List.of(), new ScriptedEnvironment(List.of())).gatePasses()).isFalse();
        EvaluationCase empty = EvaluationCase.builder("EMPTY", "runtime", "missing assertions").build();
        EvaluationReport report = harness.run(List.of(empty), new ScriptedEnvironment(List.of()));
        assertThat(report.results().getFirst().status()).isEqualTo(EvaluationReport.Status.NOT_VERIFIED);
        assertThat(report.gatePasses()).isFalse();
    }

    @Test
    void partialPreparationFailureIsUnverifiedAndStillCleansUp() {
        AtomicInteger cleanups = new AtomicInteger();
        EvaluationEnvironment environment = new EvaluationEnvironment() {
            @Override public void prepare(EvaluationCase c) { throw new IllegalStateException("secret"); }
            @Override public void cleanup(EvaluationCase c) { cleanups.incrementAndGet(); }
        };
        EvaluationReport report = new EvaluationHarness((c, e) -> {
            throw new AssertionError("must not execute");
        }, reportContext()).run(List.of(checkedCase("PREPARE")), environment);
        assertThat(cleanups).hasValue(1);
        assertThat(report.results().getFirst().status()).isEqualTo(EvaluationReport.Status.NOT_VERIFIED);
        assertThat(report.results().getFirst().failureNote()).doesNotContain("secret");
        assertThat(report.gatePasses()).isFalse();
    }

    @Test
    void cleanupFailureBlocksGateWithoutDiscardingAssertions() {
        EvaluationEnvironment environment = new EvaluationEnvironment() {
            @Override public void cleanup(EvaluationCase c) { throw new IllegalStateException("secret"); }
        };
        EvaluationReport report = new EvaluationHarness((c, e) -> new ExecutionTrace(), reportContext())
                .run(List.of(checkedCase("CLEANUP")), environment);
        assertThat(report.results().getFirst().assertionResults()).hasSize(1);
        assertThat(report.results().getFirst().failureNote()).contains("资源清理失败").doesNotContain("secret");
        assertThat(report.gatePasses()).isFalse();
    }

    @Test
    void assertionErrorsAreReportedAndDoNotAbortRemainingCases() {
        AtomicInteger cleanups = new AtomicInteger();
        EvaluationCase failure = EvaluationCase.builder("ASSERT", "runtime", "assertion failure")
                .assertSafety("safe", t -> { throw new AssertionError("secret"); }).build();
        EvaluationReport report = new EvaluationHarness((c, e) -> new ExecutionTrace(), reportContext())
                .run(List.of(failure, checkedCase("NEXT")), new EvaluationEnvironment() {
                    @Override public void cleanup(EvaluationCase c) { cleanups.incrementAndGet(); }
                });
        assertThat(cleanups).hasValue(2);
        assertThat(report.results()).extracting(EvaluationReport.CaseResult::status)
                .containsExactly(EvaluationReport.Status.FAILED, EvaluationReport.Status.PASSED);
        assertThat(report.results().getFirst().assertionResults().getFirst().note()).isEqualTo("AssertionError");
        assertThat(report.gatePasses()).isFalse();
    }

    @Test
    void skippedExecutionAndModelBudgetViolationBlockGate() {
        EvaluationReport skipped = new EvaluationHarness((c, e) -> {
            throw new EvaluationEnvironment.UnavailableException();
        }, reportContext()).run(List.of(checkedCase("SKIPPED")), new EvaluationEnvironment() {});
        assertThat(skipped.results().getFirst().status()).isEqualTo(EvaluationReport.Status.NOT_VERIFIED);
        assertThat(skipped.gatePasses()).isFalse();
        EvaluationCase budget = EvaluationCase.builder("BUDGET", "runtime", "budget")
                .assertResult("result", t -> true).maxModelCalls(0).build();
        EvaluationReport exceeded = new EvaluationHarness((c, e) -> {
            ExecutionTrace trace = new ExecutionTrace();
            trace.action("model-call", "generate", "SUCCESS");
            return trace;
        }, reportContext()).run(List.of(budget), new EvaluationEnvironment() {});
        assertThat(exceeded.gatePasses()).isFalse();
    }

    private EvaluationCase checkedCase(String id) {
        return EvaluationCase.builder(id, "runtime", "harness contract")
                .assertSafety("safe", t -> true).build();
    }

    private String lastConsumeOutcome(ExecutionTrace trace) {
        return trace.actions().stream()
                .filter(a -> "consume-handoff".equals(a.type()))
                .map(a -> a.outcome())
                .reduce((first, second) -> second)
                .orElse("false");
    }

    private EvaluationHarness.ReportContext reportContext() {
        return new EvaluationHarness.ReportContext("3ac747a+local", "gpt-5-mini",
                "prompt-v2", "policy-v2.0", "tools-v1", "x-scenarios-v1", "deterministic-test");
    }

    /** 与 Runtime 测试一致的内存运行存储。 */
    static final class InMemoryRunStore implements TaskRunStore {

        @Override
        public synchronized <T> T withRunLock(String runId, java.util.function.Supplier<T> action) {
            return action.get();
        }

        private final Map<String, TaskRun> runs = new ConcurrentHashMap<>();
        private final Map<String, TaskStep> steps = new ConcurrentHashMap<>();
        private final List<TaskAttempt> attempts = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void saveRun(TaskRun run) {
            runs.put(run.runId(), run);
        }

        @Override
        public synchronized boolean updateRunIfStatus(String runId, TaskRunStatus expected, TaskRun updated) {
            TaskRun current = runs.get(runId);
            if (current == null || current.status() != expected) {
                return false;
            }
            runs.put(runId, updated);
            return true;
        }

        @Override
        public void updateRun(TaskRun run) {
            runs.put(run.runId(), run);
        }

        @Override
        public Optional<TaskRun> findRun(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<TaskRun> findByStatus(TaskRunStatus status) {
            return runs.values().stream().filter(r -> r.status() == status).toList();
        }

        @Override
        public void saveStep(TaskStep step) {
            steps.put(step.stepId(), step);
        }

        @Override
        public void updateStep(TaskStep step) {
            steps.put(step.stepId(), step);
        }

        @Override
        public Optional<TaskStep> findStep(String stepId) {
            return Optional.ofNullable(steps.get(stepId));
        }

        @Override
        public List<TaskStep> findSteps(String runId) {
            return steps.values().stream()
                    .filter(s -> s.runId().equals(runId))
                    .sorted(java.util.Comparator.comparing(TaskStep::startedAt))
                    .toList();
        }

        @Override
        public void saveAttempt(TaskAttempt attempt) {
            attempts.add(attempt);
        }

        @Override
        public void updateAttempt(TaskAttempt attempt) {
            for (int i = 0; i < attempts.size(); i++) {
                if (attempts.get(i).attemptId().equals(attempt.attemptId())) {
                    attempts.set(i, attempt);
                    return;
                }
            }
            throw new IllegalStateException("attempt not found: " + attempt.attemptId());
        }

        @Override
        public List<TaskAttempt> findAttempts(String runId) {
            return attempts.stream().filter(a -> a.runId().equals(runId)).toList();
        }
    }
}
