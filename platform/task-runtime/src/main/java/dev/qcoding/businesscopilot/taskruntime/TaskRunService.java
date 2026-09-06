package dev.qcoding.businesscopilot.taskruntime;

import dev.qcoding.businesscopilot.aicore.AiAttemptObserver;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * 轻量业务任务运行管理（RUN-01 ~ RUN-07）。
 *
 * <p>职责：统一运行标识（runId/stepId/attemptId）、累计预算、持久化人工等待、
 * 持久恢复点、安全重试与未知结果、可解释运行时间线和有界上下文。
 * Runtime 关联业务状态，不复制或替代各业务模块自己的状态机、Repository 和权限语义：
 * 取消、恢复等对象级操作仍通过 {@link ObjectAccessPolicy} 按对象归属校验。</p>
 *
 * <p>等待确认不占用工作线程：{@link #awaitConfirmation} 只持久化等待状态并返回；
 * {@link #resumeWaitingRun} 在恢复时重新校验操作者、对象状态（回调）、上下文有效性与确认凭证，
 * 任一不满足则继续等待或按失败分类终止。</p>
 */
public class TaskRunService {

    private static final Logger log = LoggerFactory.getLogger(TaskRunService.class);

    private final TaskRunStore store;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final Clock clock;
    private final Duration staleAttemptAfter;

    public TaskRunService(TaskRunStore store,
                          CurrentActorProvider actorProvider,
                          ObjectAccessPolicy accessPolicy) {
        this(store, actorProvider, accessPolicy, Clock.systemUTC(), Duration.ofMinutes(15));
    }

    public TaskRunService(TaskRunStore store,
                          CurrentActorProvider actorProvider,
                          ObjectAccessPolicy accessPolicy,
                          Clock clock) {
        this(store, actorProvider, accessPolicy, clock, Duration.ofMinutes(15));
    }

    public TaskRunService(TaskRunStore store,
                          CurrentActorProvider actorProvider,
                          ObjectAccessPolicy accessPolicy,
                          Clock clock,
                          Duration staleAttemptAfter) {
        this.store = store;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        if (staleAttemptAfter == null || staleAttemptAfter.isZero() || staleAttemptAfter.isNegative()) {
            throw new IllegalArgumentException("中断尝试判定时长必须大于零");
        }
        this.staleAttemptAfter = staleAttemptAfter;
    }

    // ---- RUN-01：统一运行标识 ----

    /** 开始一次任务运行；必须提供有界上下文声明（数据范围、授权来源、保留时间）。 */
    public TaskRun startRun(String module, String refType, String refId, TaskRunBudget budget,
                            ContextManifest contextManifest) {
        CurrentActor actor = requireActor();
        if (contextManifest == null || !contextManifest.isValid(clock.instant())) {
            throw new IllegalArgumentException("任务运行必须声明有界上下文（数据范围、授权来源、保留时间）");
        }
        TaskRun run = new TaskRun(null, module, refType, refId, actor.actorId(),
                TaskRunStatus.RUNNING, budget != null ? budget : TaskRunBudget.unlimited(),
                contextManifest, clock.instant(), null, null, null, null);
        store.saveRun(run);
        log.info("任务运行开始：runId={}，module={}，ref={}/{}，owner={}",
                run.runId(), module, refType, refId, actor.actorId());
        return run;
    }

    /** 在运行内开始一个步骤；步骤持久化后即可作为恢复点。 */
    public TaskStep beginStep(String runId, String name) {
        return store.withRunLock(runId, () -> {
            TaskRun run = requireRunInStatus(runId, TaskRunStatus.RUNNING);
            requireValidContext(run);
            TaskStep step = new TaskStep(null, runId, name, TaskStep.TaskStepStatus.PENDING,
                    0, null, List.of(), null, clock.instant(), null);
            store.saveStep(step);
            return step;
        });
    }

    /** 步骤完成，登记证据引用（仅标识）与受限摘要。 */
    public void completeStep(TaskStep step, List<String> evidenceRefs, String summary) {
        store.withRunLock(step.runId(), () -> {
            TaskRun run = requireRunInStatus(step.runId(), TaskRunStatus.RUNNING);
            requireValidContext(run);
            TaskStep current = requirePendingStep(step.runId(), step.stepId());
            store.updateStep(new TaskStep(current.stepId(), current.runId(), current.name(),
                    TaskStep.TaskStepStatus.COMPLETED, current.attemptCount(), null,
                    evidenceRefs, summarize(summary), current.startedAt(), clock.instant()));
            return null;
        });
    }

    /** 步骤失败，登记失败分类与受限摘要。 */
    public void failStep(TaskStep step, FailureCategory category, String summary) {
        store.withRunLock(step.runId(), () -> {
            requireRunInStatus(step.runId(), TaskRunStatus.RUNNING,
                    TaskRunStatus.BUDGET_EXHAUSTED);
            TaskStep current = requirePendingStep(step.runId(), step.stepId());
            store.updateStep(new TaskStep(current.stepId(), current.runId(), current.name(),
                    TaskStep.TaskStepStatus.FAILED, current.attemptCount(), category,
                    current.evidenceRefs(), summarize(summary), current.startedAt(), clock.instant()));
            return null;
        });
    }

    // ---- RUN-02：累计预算 ----

    /** Compatibility API for callers that already have a completed model attempt. */
    public TaskAttempt recordModelAttempt(String runId, String stepId, String operation,
                                          String provider, String model,
                                          Integer inputTokens, Integer outputTokens,
                                          long latencyMs, TaskAttempt.Outcome outcome,
                                          FailureCategory failureCategory) {
        int reservation = Math.max(0, (inputTokens != null ? inputTokens : 0)
                + (outputTokens != null ? outputTokens : 0));
        TaskAttempt started = beginAttempt(runId, stepId, TaskAttempt.Kind.MODEL, operation,
                provider, model, reservation);
        return finishAttempt(runId, started.attemptId(), inputTokens, outputTokens,
                latencyMs, outcome, failureCategory, provider, model);
    }

    /** Compatibility API for callers that already have a completed tool attempt. */
    public TaskAttempt recordToolAttempt(String runId, String stepId, String operation,
                                         Integer inputTokens, Integer outputTokens,
                                         long latencyMs, TaskAttempt.Outcome outcome,
                                         FailureCategory failureCategory) {
        TaskAttempt started = beginAttempt(runId, stepId, TaskAttempt.Kind.TOOL, operation,
                null, null, 0);
        return finishAttempt(runId, started.attemptId(), inputTokens, outputTokens,
                latencyMs, outcome, failureCategory, null, null);
    }

    /** Observer passed into AiChatService; every provider retry gets its own reservation and row. */
    public AiAttemptObserver aiAttemptObserver(String runId, String stepId) {
        return new AiAttemptObserver() {
            @Override
            public String beforeAttempt(String operation, String provider, String model,
                                        int estimatedTokens) {
                return beginAttempt(runId, stepId, TaskAttempt.Kind.MODEL, operation,
                        provider, model, estimatedTokens).attemptId();
            }

            @Override
            public void afterAttempt(String attemptId, AiInvocationMetadata metadata,
                                     Throwable failure) {
                finishAttempt(runId, attemptId,
                        metadata != null ? metadata.inputTokens() : null,
                        metadata != null ? metadata.outputTokens() : null,
                        metadata != null ? metadata.latencyMs() : 0L,
                        failure == null ? TaskAttempt.Outcome.SUCCESS : TaskAttempt.Outcome.FAILURE,
                        failure == null ? null : failureCategory(failure, TaskAttempt.Kind.MODEL),
                        metadata != null ? metadata.providerName() : null,
                        metadata != null ? metadata.modelName() : null);
            }
        };
    }

    /** Pre-dispatch tool reservation, used around retrieval or side-effecting adapters. */
    public TaskAttempt beginToolAttempt(String runId, String stepId, String operation) {
        return beginAttempt(runId, stepId, TaskAttempt.Kind.TOOL, operation, null, null, 0);
    }

    public TaskAttempt finishToolAttempt(String runId, String attemptId, long latencyMs,
                                         TaskAttempt.Outcome outcome,
                                         FailureCategory failureCategory) {
        return finishAttempt(runId, attemptId, null, null, latencyMs, outcome,
                failureCategory, null, null);
    }

    private TaskAttempt beginAttempt(String runId, String stepId, TaskAttempt.Kind kind,
                                     String operation, String provider, String model,
                                     int estimatedTokens) {
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("预留 Token 不能为负数");
        }
        TaskAttempt result = store.withRunLock(runId, () -> beginAttemptLocked(
                runId, stepId, kind, operation, provider, model, estimatedTokens));
        if (result == null) {
            throw new RunBudgetExhaustedException(runId, kind.name());
        }
        return result;
    }

    private TaskAttempt beginAttemptLocked(String runId, String stepId, TaskAttempt.Kind kind,
                                           String operation, String provider, String model,
                                           int estimatedTokens) {
        TaskRun run = requireRunInStatus(runId, TaskRunStatus.RUNNING);
        requireValidContext(run);
        requirePendingStep(runId, stepId);
        Instant now = clock.instant();
        List<TaskAttempt> priorAttempts = store.findAttempts(runId);
        int priorModelCalls = (int) priorAttempts.stream()
                .filter(a -> a.kind() == TaskAttempt.Kind.MODEL).count();
        int priorToolCalls = (int) priorAttempts.stream()
                .filter(a -> a.kind() == TaskAttempt.Kind.TOOL).count();
        long reservedOrActualTokens = priorAttempts.stream()
                .mapToLong(a -> (a.inputTokens() != null ? a.inputTokens().longValue() : 0L)
                        + (a.outputTokens() != null ? a.outputTokens() : 0))
                .sum();
        TaskRunBudget budget = run.budget() != null ? run.budget() : TaskRunBudget.unlimited();
        boolean withinBudget = switch (kind) {
            case MODEL -> budget.maxModelCalls() == null || priorModelCalls < budget.maxModelCalls();
            case TOOL -> budget.maxToolCalls() == null || priorToolCalls < budget.maxToolCalls();
        };
        withinBudget = withinBudget && (budget.maxTokens() == null
                || reservedOrActualTokens + (kind == TaskAttempt.Kind.MODEL ? estimatedTokens : 0)
                <= budget.maxTokens());
        if (budget.maxTokens() != null && priorAttempts.stream()
                .filter(a -> a.outcome() != TaskAttempt.Outcome.STARTED)
                .anyMatch(this::unknownModelUsage)) {
            withinBudget = false;
        }
        boolean durationExceeded = budget.maxDuration() != null
                && Duration.between(run.startedAt(), now).compareTo(budget.maxDuration()) >= 0;
        if (!withinBudget || durationExceeded) {
            transition(run, TaskRunStatus.BUDGET_EXHAUSTED, FailureCategory.BUDGET,
                    "预算耗尽：派发前预算不足，已拒绝 " + kind.name() + " 调用");
            return null;
        }
        TaskAttempt attempt = new TaskAttempt(null, runId, stepId, kind, operation, provider, model,
                kind == TaskAttempt.Kind.MODEL ? estimatedTokens : null, null, 0L,
                TaskAttempt.Outcome.STARTED, null, now);
        store.saveAttempt(attempt);
        TaskStep step = requirePendingStep(runId, stepId);
        store.updateStep(new TaskStep(step.stepId(), step.runId(), step.name(), step.status(),
                step.attemptCount() + 1, step.failureCategory(), step.evidenceRefs(),
                step.summary(), step.startedAt(), step.endedAt()));
        return attempt;
    }

    private TaskAttempt finishAttempt(String runId, String attemptId,
                                      Integer inputTokens, Integer outputTokens,
                                      long latencyMs, TaskAttempt.Outcome outcome,
                                      FailureCategory failureCategory,
                                      String provider, String model) {
        if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)
                || latencyMs < 0 || outcome == TaskAttempt.Outcome.STARTED) {
            throw new IllegalArgumentException("尝试结果或用量无效");
        }
        TaskAttempt result = store.withRunLock(runId, () -> finishAttemptLocked(
                runId, attemptId, inputTokens, outputTokens, latencyMs, outcome,
                failureCategory, provider, model));
        if (result == null) {
            throw new RunBudgetExhaustedException(runId, "TOKENS_OR_DURATION");
        }
        return result;
    }

    private TaskAttempt finishAttemptLocked(String runId, String attemptId,
                                            Integer inputTokens, Integer outputTokens,
                                            long latencyMs, TaskAttempt.Outcome outcome,
                                            FailureCategory failureCategory,
                                            String provider, String model) {
        TaskRun run = requireRunInStatus(runId, TaskRunStatus.RUNNING);
        TaskAttempt started = store.findAttempts(runId).stream()
                .filter(a -> a.attemptId().equals(attemptId)
                        && a.outcome() == TaskAttempt.Outcome.STARTED)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATE_CONFLICT,
                        "尝试预留不存在或已完成"));
        TaskAttempt completed = new TaskAttempt(started.attemptId(), started.runId(), started.stepId(),
                started.kind(), started.operation(), provider != null ? provider : started.provider(),
                model != null ? model : started.model(), inputTokens, outputTokens, latencyMs,
                outcome, failureCategory, started.occurredAt());
        store.updateAttempt(completed);
        if (started.kind() == TaskAttempt.Kind.TOOL && outcome == TaskAttempt.Outcome.UNKNOWN) {
            transition(run, TaskRunStatus.OUTCOME_UNKNOWN, failureCategory,
                    "工具结果未知，必须核对后再继续");
            return completed;
        }
        List<TaskAttempt> attempts = store.findAttempts(runId).stream()
                .map(a -> a.attemptId().equals(attemptId) ? completed : a)
                .toList();
        TaskRunBudget budget = run.budget() != null ? run.budget() : TaskRunBudget.unlimited();
        long totalTokens = attempts.stream()
                .mapToLong(a -> (a.inputTokens() != null ? a.inputTokens().longValue() : 0L)
                        + (a.outputTokens() != null ? a.outputTokens() : 0))
                .sum();
        boolean tokenExceeded = budget.maxTokens() != null
                && (totalTokens > budget.maxTokens()
                || attempts.stream().filter(a -> a.outcome() != TaskAttempt.Outcome.STARTED)
                .anyMatch(this::unknownModelUsage));
        boolean durationExceeded = budget.maxDuration() != null
                && Duration.between(run.startedAt(), clock.instant())
                .compareTo(budget.maxDuration()) >= 0;
        if (tokenExceeded || durationExceeded) {
            transition(run, TaskRunStatus.BUDGET_EXHAUSTED, FailureCategory.BUDGET,
                    tokenExceeded ? "实际 Token 用量超出预算" : "运行耗时超出预算");
            return null;
        }
        return completed;
    }

    private FailureCategory failureCategory(Throwable failure, TaskAttempt.Kind kind) {
        if (failure instanceof BusinessException businessException) {
            return RuntimeFailureCategories.from(businessException);
        }
        return kind == TaskAttempt.Kind.MODEL ? FailureCategory.MODEL : FailureCategory.TOOL;
    }

    /** 评估当前运行是否已超出预算（用于执行循环在每次尝试前主动检查）。 */
    public boolean budgetExhausted(String runId) {
        TaskRun run = requireAccessibleRun(runId);
        Instant now = clock.instant();
        TaskRunBudget budget = run.budget() != null ? run.budget() : TaskRunBudget.unlimited();
        List<TaskAttempt> attempts = store.findAttempts(runId);
        long tokens = attempts.stream()
                .mapToLong(a -> (a.inputTokens() != null ? a.inputTokens().longValue() : 0L)
                        + (a.outputTokens() != null ? a.outputTokens() : 0))
                .sum();
        return (budget.maxModelCalls() != null
                && attempts.stream().filter(a -> a.kind() == TaskAttempt.Kind.MODEL).count()
                        >= budget.maxModelCalls())
                || (budget.maxToolCalls() != null
                        && attempts.stream().filter(a -> a.kind() == TaskAttempt.Kind.TOOL).count()
                                >= budget.maxToolCalls())
                || (budget.maxTokens() != null && (tokens >= budget.maxTokens()
                        || attempts.stream().anyMatch(this::unknownModelUsage)))
                || (budget.maxDuration() != null
                        && Duration.between(run.startedAt(), now).compareTo(budget.maxDuration()) >= 0);
    }

    // ---- RUN-03：持久化人工等待与恢复 ----

    /** 挂起运行等待人工确认；只持久化状态，不占用工作线程。 */
    public TaskRun awaitConfirmation(String runId, String reason) {
        return store.withRunLock(runId, () -> {
            TaskRun run = requireRunInStatus(runId, TaskRunStatus.RUNNING);
            requireValidContext(run);
            TaskRun updated = transition(run, TaskRunStatus.WAITING_CONFIRMATION, null, reason);
            log.info("任务运行等待人工确认：runId={}，reason={}", runId, reason);
            return updated;
        });
    }

    /**
     * 恢复等待中的运行：重新校验操作者（对象归属）、对象状态（回调）、上下文有效性。
     * 任一校验失败时抛出业务异常并保持等待状态（或按失败分类终止），不自动补发可执行授权。
     *
     * @param objectStateCheck 业务对象状态校验回调；返回 false 表示对象已不可继续
     * @param confirmationValid 确认凭证有效性回调；返回 false 表示凭证已过期或已消费
     */
    public TaskRun resumeWaitingRun(String runId, Supplier<Boolean> objectStateCheck,
                                    Supplier<Boolean> confirmationValid) {
        return store.withRunLock(runId, () -> {
            TaskRun run = requireRunInStatus(runId, TaskRunStatus.WAITING_CONFIRMATION);
            CurrentActor actor = requireActor();
            if (accessPolicy == null || !accessPolicy.allowed(actor, ObjectAction.CONFIRM,
                    run.ownerActorId(), null, false)) {
                auditDeniedResume(run, actor);
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            Instant now = clock.instant();
            if (run.contextManifest() == null || run.contextManifest().dataScopeRefs().isEmpty()
                    || !run.contextManifest().isValid(now)) {
                log.warn("恢复失败：运行上下文已失效：runId={}", runId);
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "运行上下文已失效，需重新发起任务");
            }
            if (objectStateCheck == null || !Boolean.TRUE.equals(objectStateCheck.get())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "业务对象状态不允许继续执行");
            }
            if (confirmationValid == null || !Boolean.TRUE.equals(confirmationValid.get())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "确认凭证已过期或已消费，需重新确认");
            }
            return transition(run, TaskRunStatus.RUNNING, null, null, actor.actorId());
        });
    }

    // ---- RUN-04：持久恢复点与进程重启恢复 ----

    /**
     * 收敛进程中断留下的 STARTED 预留。模型调用按保守预留用量记失败并等待人工恢复；
     * 工具调用可能已经产生副作用，因此进入 OUTCOME_UNKNOWN，核对前不得重试。
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${business-copilot.task-runtime.recovery-scan-delay-ms:60000}")
    public int reconcileInterruptedAttempts() {
        return reconcileInterruptedAttempts(staleAttemptAfter);
    }

    public int reconcileInterruptedAttempts(Duration staleAfter) {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("中断尝试判定时长必须大于零");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(staleAfter);
        int reconciled = 0;
        for (String runId : store.findRunIdsWithStartedAttemptsBefore(cutoff)) {
            Boolean changed = store.withRunLock(runId,
                    () -> reconcileInterruptedAttemptsLocked(runId, cutoff, now));
            if (Boolean.TRUE.equals(changed)) {
                reconciled++;
            }
        }
        if (reconciled > 0) {
            log.warn("已收敛进程中断遗留的任务运行：count={}", reconciled);
        }
        return reconciled;
    }

    private boolean reconcileInterruptedAttemptsLocked(String runId, Instant cutoff, Instant now) {
        TaskRun run = store.findRun(runId).orElse(null);
        if (run == null || run.status() != TaskRunStatus.RUNNING) {
            return false;
        }
        List<TaskAttempt> interrupted = store.findAttempts(runId).stream()
                .filter(attempt -> attempt.outcome() == TaskAttempt.Outcome.STARTED
                        && !attempt.occurredAt().isAfter(cutoff))
                .toList();
        if (interrupted.isEmpty()) {
            return false;
        }
        boolean hasToolAttempt = interrupted.stream()
                .anyMatch(attempt -> attempt.kind() == TaskAttempt.Kind.TOOL);
        for (TaskAttempt started : interrupted) {
            boolean tool = started.kind() == TaskAttempt.Kind.TOOL;
            long latencyMs = Math.max(0L, Duration.between(started.occurredAt(), now).toMillis());
            store.updateAttempt(new TaskAttempt(started.attemptId(), started.runId(), started.stepId(),
                    started.kind(), started.operation(), started.provider(), started.model(),
                    tool ? null : started.inputTokens(), tool ? null : 0, latencyMs,
                    tool ? TaskAttempt.Outcome.UNKNOWN : TaskAttempt.Outcome.FAILURE,
                    tool ? FailureCategory.UNKNOWN_OUTCOME : FailureCategory.PROVIDER,
                    started.occurredAt()));
        }
        if (hasToolAttempt) {
            transition(run, TaskRunStatus.OUTCOME_UNKNOWN, FailureCategory.UNKNOWN_OUTCOME,
                    "进程中断时工具调用结果未知，必须核对回执后再继续");
        } else {
            transition(run, TaskRunStatus.WAITING_CONFIRMATION, FailureCategory.PROVIDER,
                    "进程中断导致模型尝试未完成，已保留恢复点并等待人工恢复");
        }
        return true;
    }

    /**
     * 进程重启后的恢复计划：列出可恢复的等待中/未知结果运行，
     * 并给出每个运行已完成与可重试的步骤。仅靠持久记录判断，不依赖内存状态。
     */
    public List<RecoveryPlan> recoveryPlans() {
        CurrentActor actor = requireActor();
        return java.util.stream.Stream.concat(store.findByStatus(TaskRunStatus.WAITING_CONFIRMATION).stream(),
                        store.findByStatus(TaskRunStatus.OUTCOME_UNKNOWN).stream())
                .filter(run -> accessPolicy != null && accessPolicy.allowed(actor, ObjectAction.EXECUTE,
                        run.ownerActorId(), null, false))
                .map(run -> new RecoveryPlan(run, completedSteps(run.runId()),
                        run.status() == TaskRunStatus.OUTCOME_UNKNOWN ? List.of() : retryableSteps(run.runId()),
                        run.status().name()))
                .collect(java.util.stream.Collectors.toList());
    }

    /** 单个运行的恢复计划。 */
    public RecoveryPlan recoveryPlan(String runId) {
        TaskRun run = requireAccessibleRun(runId);
        return new RecoveryPlan(run, completedSteps(runId),
                run.status() == TaskRunStatus.WAITING_CONFIRMATION ? retryableSteps(runId) : List.of(),
                run.status().name());
    }

    private List<TaskStep> completedSteps(String runId) {
        return store.findSteps(runId).stream()
                .filter(step -> step.status() == TaskStep.TaskStepStatus.COMPLETED)
                .toList();
    }

    private List<TaskStep> retryableSteps(String runId) {
        return store.findSteps(runId).stream()
                .filter(step -> step.status() == TaskStep.TaskStepStatus.PENDING
                        || step.status() == TaskStep.TaskStepStatus.FAILED)
                .toList();
    }

    // ---- RUN-05：安全重试与未知结果 ----

    /** 副作用动作结果未知：先标记 OUTCOME_UNKNOWN，核对完成前不允许以重试掩盖重复执行。 */
    public TaskRun markOutcomeUnknown(String runId, FailureCategory category, String reason) {
        return store.withRunLock(runId, () -> transition(requireRunInStatus(runId, TaskRunStatus.RUNNING),
                TaskRunStatus.OUTCOME_UNKNOWN, category, reason));
    }

    /** 未知结果核对完成后的收敛：核对成功（副作用恰好一次）→ 继续运行。 */
    public TaskRun resolveOutcomeUnknown(String runId, Supplier<Boolean> receiptVerified) {
        return store.withRunLock(runId, () -> {
            TaskRun run = requireRunInStatus(runId, TaskRunStatus.OUTCOME_UNKNOWN);
            requireValidContext(run);
            if (receiptVerified == null || !Boolean.TRUE.equals(receiptVerified.get())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "外部结果尚未核对");
            }
            return transition(run, TaskRunStatus.RUNNING, null, "未知结果已核对");
        });
    }

    // ---- RUN-06：可解释运行时间线与终态 ----

    /** RUN-07：保留期治理——删除上下文已过期的运行（包括等待与未知结果）。 */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 3 * * *")
    public int purgeExpiredRuns() {
        int purged = store.purgeExpiredRuns(clock.instant());
        if (purged > 0) {
            log.info("保留期清理完成：删除上下文过期运行 {} 条", purged);
        }
        return purged;
    }

    /** 运行时间线：步骤、尝试、停止原因与确认人；不包含敏感全文。 */
    public RunTimeline timeline(String runId) {
        TaskRun run = requireAccessibleRun(runId);
        return new RunTimeline(run, store.findSteps(runId), store.findAttempts(runId));
    }

    public TaskRun completeRun(String runId) {
        return store.withRunLock(runId, () -> {
            TaskRun run = requireRunInStatus(runId, TaskRunStatus.RUNNING);
            requireValidContext(run);
            if (store.findSteps(runId).stream().anyMatch(step -> step.status() != TaskStep.TaskStepStatus.COMPLETED)) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "仍有未完成步骤");
            }
            return transition(run, TaskRunStatus.SUCCEEDED, null, null);
        });
    }

    public TaskRun failRun(String runId, FailureCategory category, String reason) {
        return store.withRunLock(runId, () -> {
            TaskRun run = requireAccessibleRun(runId);
            if (run.status() == TaskRunStatus.BUDGET_EXHAUSTED) {
                return run;
            }
            return transition(requireRunInStatus(runId, TaskRunStatus.RUNNING,
                            TaskRunStatus.WAITING_CONFIRMATION),
                    TaskRunStatus.FAILED, category, reason);
        });
    }

    /**
     * 取消运行：校验操作者对象归属（X-08）；停止可取消步骤，
     * 已发生动作的事实（尝试记录）全部保留。
     */
    public TaskRun cancelRun(String runId, String reason) {
        return store.withRunLock(runId, () -> {
            TaskRun run = requireRunInStatus(runId, TaskRunStatus.RUNNING, TaskRunStatus.WAITING_CONFIRMATION);
            CurrentActor actor = requireActor();
            if (accessPolicy == null || !accessPolicy.allowed(actor, ObjectAction.CANCEL,
                    run.ownerActorId(), null, false)) {
                log.warn("拒绝越权取消任务运行：runId={}，owner={}，actor={}",
                        runId, run.ownerActorId(), actor.actorId());
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            TaskRun updated = transition(run, TaskRunStatus.CANCELLED, null, reason);
            // 未完成步骤标记为 SKIPPED；已完成步骤的事实保留。
            store.findSteps(runId).stream()
                    .filter(step -> step.status() == TaskStep.TaskStepStatus.PENDING
                            || step.status() == TaskStep.TaskStepStatus.FAILED)
                    .forEach(step -> store.updateStep(new TaskStep(step.stepId(), step.runId(),
                            step.name(), TaskStep.TaskStepStatus.SKIPPED, step.attemptCount(),
                            step.failureCategory(), step.evidenceRefs(), step.summary(),
                            step.startedAt(), clock.instant())));
            log.info("任务运行已取消：runId={}，actor={}，reason={}", runId, actor.actorId(), reason);
            return updated;
        });
    }

    // ---- 内部工具 ----

    private TaskRun requireRunInStatus(String runId, TaskRunStatus... allowed) {
        TaskRun run = requireAccessibleRun(runId);
        for (TaskRunStatus status : allowed) {
            if (run.status() == status) {
                return run;
            }
        }
        throw new BusinessException(ErrorCode.STATE_CONFLICT,
                "任务运行当前状态为 " + run.status() + "，不允许该操作");
    }

    private void requireValidContext(TaskRun run) {
        if (run.contextManifest() == null || run.contextManifest().dataScopeRefs().isEmpty()
                || !run.contextManifest().isValid(clock.instant())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "运行上下文已失效，需重新发起任务");
        }
    }

    private TaskRun transition(TaskRun run, TaskRunStatus to, FailureCategory category, String reason) {
        return transition(run, to, category, reason, run.confirmedByActorId());
    }

    private TaskRun transition(TaskRun run, TaskRunStatus to, FailureCategory category,
                               String reason, String confirmedBy) {
        if (run.status() == to) {
            return run;
        }
        if (!run.status().canTransitionTo(to)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "不允许的任务状态转换：" + run.status() + " → " + to);
        }
        TaskRun updated = new TaskRun(run.runId(), run.module(), run.refType(), run.refId(),
                run.ownerActorId(), to, run.budget(), run.contextManifest(),
                run.startedAt(), to.terminal() || to == TaskRunStatus.SUCCEEDED ? clock.instant() : null,
                category, summarize(reason), confirmedBy);
        if (!store.updateRunIfStatus(run.runId(), run.status(), updated)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "任务状态已变化，请重新获取");
        }
        return updated;
    }

    private TaskRun requireAccessibleRun(String runId) {
        CurrentActor actor = requireActor();
        TaskRun run = store.findRun(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (accessPolicy == null || !accessPolicy.allowed(actor, ObjectAction.EXECUTE,
                run.ownerActorId(), null, false)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return run;
    }

    private TaskStep requirePendingStep(String runId, String stepId) {
        TaskStep step = store.findStep(stepId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!runId.equals(step.runId())) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (step.status() != TaskStep.TaskStepStatus.PENDING) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "步骤状态不允许执行");
        }
        return step;
    }

    private boolean unknownModelUsage(TaskAttempt attempt) {
        return attempt.kind() == TaskAttempt.Kind.MODEL
                && (attempt.inputTokens() == null || attempt.outputTokens() == null);
    }

    private CurrentActor requireActor() {
        CurrentActor actor = actorProvider != null ? actorProvider.currentActor() : null;
        if (actor == null || !actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return actor;
    }

    private void auditDeniedResume(TaskRun run, CurrentActor actor) {
        log.warn("拒绝越权恢复任务运行：runId={}，owner={}，actor={}",
                run.runId(), run.ownerActorId(), actor.actorId());
    }

    /** 限长摘要：运行记录以结果、证据引用和必要摘要为主（RUN-06）。 */
    private String summarize(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() <= 500 ? summary : summary.substring(0, 500);
    }

    /** 预算耗尽异常：调用方应停止执行循环或转人工，不得继续重试。 */
    public static final class RunBudgetExhaustedException extends BusinessException {

        private final String runId;

        public RunBudgetExhaustedException(String runId, String kind) {
            super(ErrorCode.AI_MODEL_ERROR, "任务预算已耗尽，已停止执行：" + kind);
            this.runId = runId;
        }

        public String runId() {
            return runId;
        }
    }

    /** 恢复计划：已完成步骤与可重试步骤（RUN-04）。 */
    public record RecoveryPlan(TaskRun run, List<TaskStep> completedSteps,
                               List<TaskStep> retryableSteps, String sourceStatus) {
    }

    /** 运行时间线（RUN-06）：运行 + 步骤 + 尝试，默认不包含敏感全文。 */
    public record RunTimeline(TaskRun run, List<TaskStep> steps, List<TaskAttempt> attempts) {
    }
}
