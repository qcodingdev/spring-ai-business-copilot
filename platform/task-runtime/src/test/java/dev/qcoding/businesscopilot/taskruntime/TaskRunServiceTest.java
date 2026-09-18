package dev.qcoding.businesscopilot.taskruntime;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务运行时核心语义测试：状态机、预算、持久等待与恢复、安全重试与未知结果（X-02 ~ X-08）。
 *
 * <p>使用内存存储模拟持久层；“进程重启”通过新建 Service 实例（共享存储）模拟，
 * 验证恢复只依赖持久记录（RUN-04）。</p>
 */
class TaskRunServiceTest {

    private InMemoryTaskRunStore store;
    private final AtomicReference<String> currentActorId = new AtomicReference<>("operator-1");
    private final AtomicReference<BusinessRole> currentRole = new AtomicReference<>(BusinessRole.OPERATOR);
    private Clock clock;

    @BeforeEach
    void setUp() {
        store = new InMemoryTaskRunStore();
        clock = Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC);
    }

    private TaskRunService service() {
        CurrentActorProvider actorProvider = () -> new CurrentActor(
                currentActorId.get(), java.util.Set.of(currentRole.get()));
        return new TaskRunService(store, actorProvider,
                new DefaultObjectAccessPolicy(), clock);
    }

    private ContextManifest manifest() {
        return new ContextManifest(List.of("handoff:42"), "confirmation:abc",
                Duration.ofHours(24), Instant.parse("2026-08-29T10:00:00Z"));
    }

    // ---- X-02：等待人工确认时进程重启，等待状态持久化；恢复后重新验证 ----

    @Test
    @DisplayName("waiting state survives restart and resume re-validates actor, object and credential")
    void waitingSurvivesRestartAndResumeRevalidates() {
        TaskRunService first = service();
        TaskRun run = first.startRun("report", "handoff", "42",
                new TaskRunBudget(3, null, null, null), manifest());
        first.awaitConfirmation(run.runId(), "等待草稿人工确认");

        // 模拟进程重启：新服务实例共享持久存储
        TaskRunService second = service();
        assertThat(second.timeline(run.runId()).run().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);

        // 确认凭证已消费：拒绝恢复，保持等待，不自动补发授权
        assertThatThrownBy(() -> second.resumeWaitingRun(run.runId(), () -> true, () -> false))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode().name())
                        .isEqualTo("STATE_CONFLICT"));
        assertThat(store.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);

        // 凭证有效：恢复正常继续
        TaskRun resumed = second.resumeWaitingRun(run.runId(), () -> true, () -> true);
        assertThat(resumed.status()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(resumed.confirmedByActorId()).isEqualTo("operator-1");
    }

    // ---- X-03：确认已过期或已经消费 → 拒绝继续执行 ----

    @Test
    @DisplayName("expired or consumed confirmation refuses to resume")
    void expiredConfirmationRefusesResume() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("report", "handoff", "43",
                TaskRunBudget.unlimited(), manifest());
        runtime.awaitConfirmation(run.runId(), "等待确认");

        assertThatThrownBy(() -> runtime.resumeWaitingRun(run.runId(), () -> true, () -> false))
                .isInstanceOf(BusinessException.class);
        assertThat(runtime.timeline(run.runId()).run().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);
    }

    // ---- X-08：用户取消时校验操作者权限，保留已发生动作事实 ----

    @Test
    @DisplayName("cancel validates object ownership and keeps happened actions")
    void cancelValidatesOwnershipAndKeepsFacts() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("data", "execution", "exec-9",
                TaskRunBudget.unlimited(), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "execute-sql");
        runtime.recordModelAttempt(run.runId(), step.stepId(), "sql-generation",
                "openai", "gpt-5-mini", 100, 50, 800,
                TaskAttempt.Outcome.SUCCESS, null);

        currentActorId.set("operator-2");
        assertThatThrownBy(() -> runtime.cancelRun(run.runId(), "用户取消"))
                .isInstanceOf(BusinessException.class);
        assertThat(store.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.RUNNING);

        currentActorId.set("operator-1");
        TaskRun cancelled = runtime.cancelRun(run.runId(), "用户取消");
        assertThat(cancelled.status()).isEqualTo(TaskRunStatus.CANCELLED);
        assertThat(cancelled.confirmedByActorId()).isNull();
        // 已发生动作的事实保留：尝试记录仍在时间线里
        assertThat(runtime.timeline(run.runId()).attempts()).hasSize(1);
        assertThat(runtime.timeline(run.runId()).steps())
                .allSatisfy(s -> assertThat(s.status()).isEqualTo(TaskStep.TaskStepStatus.SKIPPED));
    }

    // ---- X-05 / X-06：模型次数与 Token 预算，所有重试计入同一预算 ----

    @Test
    @DisplayName("model call budget counts every attempt including retries")
    void modelBudgetCountsRetries() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("data", "execution", "exec-10",
                new TaskRunBudget(2, null, null, null), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "generate");

        runtime.recordModelAttempt(run.runId(), step.stepId(), "gen", null, null,
                10, 5, 100, TaskAttempt.Outcome.SUCCESS, null);
        runtime.recordModelAttempt(run.runId(), step.stepId(), "gen-retry", null, null,
                10, 5, 100, TaskAttempt.Outcome.SUCCESS, null);
        // 第三次尝试（语言重试）超出预算：运行停止且抛出预算异常
        assertThatThrownBy(() -> runtime.recordModelAttempt(run.runId(), step.stepId(),
                "gen-retry-2", null, null, 10, 5, 100, TaskAttempt.Outcome.SUCCESS, null))
                .isInstanceOf(TaskRunService.RunBudgetExhaustedException.class);
        assertThat(store.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.BUDGET_EXHAUSTED);
        // 派发前即拒绝第三次调用，账本只包含真实获准的两次尝试。
        assertThat(runtime.timeline(run.runId()).attempts()).hasSize(2);
    }

    @Test
    @DisplayName("provider attempt is reserved durably before dispatch and then finalized")
    void providerAttemptIsReservedBeforeDispatch() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("report", "generation", "pre-dispatch",
                new TaskRunBudget(1, null, 10_000, null), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "generate");
        var observer = runtime.aiAttemptObserver(run.runId(), step.stepId());

        String attemptId = observer.beforeAttempt("report.generation", "provider", "model", 5_000);
        assertThat(runtime.timeline(run.runId()).attempts()).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.attemptId()).isEqualTo(attemptId);
                    assertThat(attempt.outcome()).isEqualTo(TaskAttempt.Outcome.STARTED);
                    assertThat(attempt.inputTokens()).isEqualTo(5_000);
                });

        observer.afterAttempt(attemptId,
                new dev.qcoding.businesscopilot.aicore.AiInvocationMetadata(
                        "provider", "model", "req-1", 120, 80, "stop", 30), null);
        assertThat(runtime.timeline(run.runId()).attempts()).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(TaskAttempt.Outcome.SUCCESS);
                    assertThat(attempt.inputTokens()).isEqualTo(120);
                    assertThat(attempt.outputTokens()).isEqualTo(80);
                });
        assertThatThrownBy(() -> observer.beforeAttempt(
                "report.generation", "provider", "model", 5_000))
                .isInstanceOf(TaskRunService.RunBudgetExhaustedException.class);
        assertThat(runtime.timeline(run.runId()).attempts()).hasSize(1);
    }

    @Test
    @DisplayName("token budget covers summed usage across attempts")
    void tokenBudgetCoversSummedUsage() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("knowledge", "answer", "q-1",
                new TaskRunBudget(null, null, 200, null), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "answer");

        runtime.recordModelAttempt(run.runId(), step.stepId(), "answer", null, null,
                80, 40, 100, TaskAttempt.Outcome.SUCCESS, null);
        // 第二次尝试累计 240 > 200：预算耗尽
        assertThatThrownBy(() -> runtime.recordModelAttempt(run.runId(), step.stepId(),
                "answer-retry", null, null, 80, 40, 100, TaskAttempt.Outcome.SUCCESS, null))
                .isInstanceOf(TaskRunService.RunBudgetExhaustedException.class);
        assertThat(store.findRun(run.runId()).orElseThrow().status())
                .isEqualTo(TaskRunStatus.BUDGET_EXHAUSTED);
    }

    // ---- X-07 / RUN-05：工具失败结果未知，先核对再继续，不以重试掩盖重复执行 ----

    @Test
    @DisplayName("unknown outcome blocks execution until reconciled")
    void unknownOutcomeBlocksUntilReconciled() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("support", "note-writeback", "ticket-7",
                TaskRunBudget.unlimited(), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "write-note");
        runtime.recordToolAttempt(run.runId(), step.stepId(), "vendor-note-api",
                null, null, 300, TaskAttempt.Outcome.UNKNOWN, FailureCategory.PROVIDER);

        TaskRun unknown = runtime.timeline(run.runId()).run();
        assertThat(unknown.status()).isEqualTo(TaskRunStatus.OUTCOME_UNKNOWN);

        // 未核对前不允许新尝试：状态冲突拒绝重复执行路径
        assertThatThrownBy(() -> runtime.recordToolAttempt(run.runId(), step.stepId(),
                "vendor-note-api-retry", null, null, 300,
                TaskAttempt.Outcome.SUCCESS, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态");
        assertThatThrownBy(() -> runtime.markOutcomeUnknown(run.runId(), FailureCategory.PROVIDER, "again"))
                .isInstanceOf(BusinessException.class);

        // 核对完成（副作用恰好一次）→ 恢复运行
        TaskRun resolved = runtime.resolveOutcomeUnknown(run.runId(), () -> true);
        assertThat(resolved.status()).isEqualTo(TaskRunStatus.RUNNING);
    }

    // ---- X-04：两个执行者竞争 —— 条件状态更新保证只有一个领取成功 ----

    @Test
    @DisplayName("concurrent resume of the same waiting run succeeds only once")
    void concurrentResumeSucceedsOnlyOnce() throws Exception {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("report", "handoff", "44",
                TaskRunBudget.unlimited(), manifest());
        runtime.awaitConfirmation(run.runId(), "等待确认");

        var start = new java.util.concurrent.CountDownLatch(1);
        var confirmations = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> resume = () -> {
                start.await();
                try {
                    service().resumeWaitingRun(run.runId(), () -> true, () -> {
                        confirmations.incrementAndGet();
                        return true;
                    });
                    return true;
                } catch (BusinessException ex) { return false; }
            };
            var first = executor.submit(resume);
            var second = executor.submit(resume);
            start.countDown();
            assertThat(List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(confirmations).hasValue(1);
    }

    // ---- X-01 / RUN-04：恢复计划区分已完成步骤和可重试步骤 ----

    @Test
    @DisplayName("recovery plan distinguishes completed and retryable steps")
    void recoveryPlanDistinguishesSteps() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("report", "handoff", "45",
                TaskRunBudget.unlimited(), manifest());
        TaskStep fetch = runtime.beginStep(run.runId(), "fetch-sources");
        runtime.completeStep(fetch, List.of("source:1", "source:2"), "采集完成");
        TaskStep generate = runtime.beginStep(run.runId(), "generate-draft");
        runtime.failStep(generate, FailureCategory.MODEL, "模型超时");
        runtime.awaitConfirmation(run.runId(), "模型失败后等待恢复");

        TaskRunService.RecoveryPlan plan = runtime.recoveryPlan(run.runId());
        assertThat(plan.completedSteps()).extracting(TaskStep::name).containsExactly("fetch-sources");
        assertThat(plan.retryableSteps()).extracting(TaskStep::name).containsExactly("generate-draft");
    }

    @Test
    @DisplayName("restart reconciliation closes stale reservations without blind retry")
    void restartReconciliationClosesStaleReservationsWithoutBlindRetry() {
        TaskRunService runtime = service();
        TaskRun modelRun = runtime.startRun("report", "generation", "crashed-model",
                new TaskRunBudget(3, 3, 20_000, Duration.ofHours(1)), manifest());
        TaskStep modelStep = runtime.beginStep(modelRun.runId(), "generate");
        String modelAttemptId = runtime.aiAttemptObserver(modelRun.runId(), modelStep.stepId())
                .beforeAttempt("report.generate", "provider", "model", 5_000);

        TaskRun toolRun = runtime.startRun("knowledge", "retrieval", "crashed-tool",
                TaskRunBudget.unlimited(), manifest());
        TaskStep toolStep = runtime.beginStep(toolRun.runId(), "retrieve");
        String toolAttemptId = runtime.beginToolAttempt(
                toolRun.runId(), toolStep.stepId(), "knowledge.retrieve").attemptId();

        clock = Clock.fixed(Instant.parse("2026-08-28T10:20:00Z"), ZoneOffset.UTC);
        TaskRunService restarted = service();
        assertThat(restarted.reconcileInterruptedAttempts(Duration.ofMinutes(15))).isEqualTo(2);

        TaskRunService.RecoveryPlan modelPlan = restarted.recoveryPlan(modelRun.runId());
        assertThat(modelPlan.run().status()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(modelPlan.run().stopReason()).contains("从原业务页");
        assertThat(modelPlan.retryableSteps()).isEmpty();
        assertThat(restarted.timeline(modelRun.runId()).steps()).singleElement()
                .extracting(TaskStep::status).isEqualTo(TaskStep.TaskStepStatus.FAILED);
        assertThat(modelPlan.run().failureCategory()).isEqualTo(FailureCategory.PROVIDER);
        assertThat(restarted.timeline(modelRun.runId()).attempts()).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.attemptId()).isEqualTo(modelAttemptId);
                    assertThat(attempt.outcome()).isEqualTo(TaskAttempt.Outcome.FAILURE);
                    assertThat(attempt.inputTokens()).isEqualTo(5_000);
                    assertThat(attempt.outputTokens()).isZero();
                });

        assertThat(restarted.timeline(toolRun.runId()).run().status())
                .isEqualTo(TaskRunStatus.OUTCOME_UNKNOWN);
        assertThat(restarted.timeline(toolRun.runId()).attempts()).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.attemptId()).isEqualTo(toolAttemptId);
                    assertThat(attempt.outcome()).isEqualTo(TaskAttempt.Outcome.UNKNOWN);
                    assertThat(attempt.failureCategory()).isEqualTo(FailureCategory.UNKNOWN_OUTCOME);
                });
        assertThat(restarted.reconcileInterruptedAttempts(Duration.ofMinutes(15))).isZero();
    }

    // ---- RUN-07：上下文过期后恢复被拒绝 ----

    @Test
    @DisplayName("expired context manifest refuses resume")
    void expiredContextRefusesResume() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("knowledge", "answer", "q-2",
                TaskRunBudget.unlimited(), manifest());
        runtime.awaitConfirmation(run.runId(), "等待确认");
        clock = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> service().resumeWaitingRun(run.runId(), () -> true, () -> true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上下文已失效");
    }

    // ---- 状态机：终态不可再转换 ----

    @Test
    @DisplayName("terminal runs reject further transitions")
    void terminalRunsRejectTransitions() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("data", "execution", "exec-11",
                TaskRunBudget.unlimited(), manifest());
        runtime.completeRun(run.runId());

        assertThatThrownBy(() -> runtime.failRun(run.runId(), FailureCategory.MODEL, "late"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runtime.cancelRun(run.runId(), "late"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("timeline keeps bounded summaries and evidence ids only")
    void timelineKeepsBoundedSummaries() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("data", "execution", "exec-12",
                TaskRunBudget.unlimited(), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "explain");
        runtime.completeStep(step, List.of("result:9"), "很".repeat(600));

        assertThat(runtime.timeline(run.runId()).steps().get(0).summary()).hasSize(500);
        assertThat(runtime.timeline(run.runId()).steps().get(0).evidenceRefs())
                .containsExactly("result:9");
    }

    @Test
    void staleStepsCannotOverwriteAttemptsOrCancellation() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("data", "execution", "stale", TaskRunBudget.unlimited(), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "generate");
        runtime.recordModelAttempt(run.runId(), step.stepId(), "gen", null, null,
                10, 5, 100, TaskAttempt.Outcome.SUCCESS, null);
        runtime.completeStep(step, List.of("evidence:1"), "complete");
        assertThat(store.findStep(step.stepId()).orElseThrow().attemptCount()).isEqualTo(1);
        TaskStep unfinished = runtime.beginStep(run.runId(), "unfinished");
        runtime.cancelRun(run.runId(), "cancel");
        assertThatThrownBy(() -> runtime.completeStep(unfinished, List.of(), "late"))
                .isInstanceOf(BusinessException.class);
        assertThat(store.findStep(unfinished.stepId()).orElseThrow().status())
                .isEqualTo(TaskStep.TaskStepStatus.SKIPPED);
    }

    @Test
    void allRunAccessChecksOwnershipAndStepMembership() {
        TaskRunService runtime = service();
        TaskRun first = runtime.startRun("data", "execution", "first", TaskRunBudget.unlimited(), manifest());
        TaskRun second = runtime.startRun("report", "handoff", "second", TaskRunBudget.unlimited(), manifest());
        TaskStep foreign = runtime.beginStep(second.runId(), "generate");
        assertThatThrownBy(() -> runtime.recordModelAttempt(first.runId(), foreign.stepId(), "gen", null,
                null, 1, 1, 1, TaskAttempt.Outcome.SUCCESS, null)).isInstanceOf(BusinessException.class);
        assertThat(store.findAttempts(first.runId())).isEmpty();
        currentActorId.set("other");
        assertThatThrownBy(() -> runtime.timeline(first.runId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runtime.beginStep(first.runId(), "unauthorized")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runtime.completeRun(first.runId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void waitingAndUnknownStatesCannotBypassVerification() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("support", "note", "unknown", TaskRunBudget.unlimited(), manifest());
        runtime.awaitConfirmation(run.runId(), "wait");
        assertThatThrownBy(() -> runtime.resumeWaitingRun(run.runId(), null, () -> true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runtime.resumeWaitingRun(run.runId(), () -> true, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runtime.completeRun(run.runId())).isInstanceOf(BusinessException.class);
        runtime.resumeWaitingRun(run.runId(), () -> true, () -> true);
        runtime.markOutcomeUnknown(run.runId(), FailureCategory.PROVIDER, "unknown");
        assertThatThrownBy(() -> runtime.resolveOutcomeUnknown(run.runId(), () -> false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runtime.completeRun(run.runId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runtime.cancelRun(run.runId(), "hide unknown")).isInstanceOf(BusinessException.class);
        assertThat(runtime.recoveryPlan(run.runId()).retryableSteps()).isEmpty();
    }

    @Test
    void unknownUsageCannotBeTreatedAsFreeTokens() {
        TaskRunService runtime = service();
        TaskRun run = runtime.startRun("data", "execution", "tokens",
                new TaskRunBudget(3, null, 200, null), manifest());
        TaskStep step = runtime.beginStep(run.runId(), "generate");
        assertThatThrownBy(() -> runtime.recordModelAttempt(run.runId(), step.stepId(), "gen", null,
                null, null, null, 1, TaskAttempt.Outcome.SUCCESS, null))
                .isInstanceOf(TaskRunService.RunBudgetExhaustedException.class);
        assertThat(runtime.timeline(run.runId()).attempts().getFirst().inputTokens()).isNull();
        assertThat(runtime.timeline(run.runId()).run().status()).isEqualTo(TaskRunStatus.BUDGET_EXHAUSTED);
    }

    /** 简单内存存储：模拟持久层并支持条件更新语义。 */
    private static final class InMemoryTaskRunStore implements TaskRunStore {

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
            return runs.values().stream().filter(run -> run.status() == status).toList();
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
                    .filter(step -> step.runId().equals(runId))
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
        }

        @Override
        public List<TaskAttempt> findAttempts(String runId) {
            List<TaskAttempt> result = new ArrayList<>();
            for (TaskAttempt attempt : attempts) {
                if (attempt.runId().equals(runId)) {
                    result.add(attempt);
                }
            }
            return result;
        }
    }
}
