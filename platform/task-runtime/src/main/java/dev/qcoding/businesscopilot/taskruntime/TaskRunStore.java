package dev.qcoding.businesscopilot.taskruntime;

import java.util.List;
import java.util.Optional;

/**
 * 运行、步骤与尝试的持久化契约（RUN-03/RUN-04）。
 *
 * <p>等待确认不占用工作线程的前提是等待状态持久化；进程重启后的恢复依赖
 * 已完成步骤与尝试记录的持久化，不存在仅靠内存判断完成的关键任务。</p>
 */
public interface TaskRunStore {

    /** 在同一数据库事务中锁定运行行，串行化状态、步骤和尝试更新。 */
    <T> T withRunLock(String runId, java.util.function.Supplier<T> action);

    void saveRun(TaskRun run);

    /** 条件更新：仅当当前状态等于 expectedStatus 时更新，用于并发领取与恢复竞争控制。 */
    boolean updateRunIfStatus(String runId, TaskRunStatus expectedStatus, TaskRun updated);

    /** 无条件更新（终态收敛等已由服务层状态机保证）。 */
    void updateRun(TaskRun run);

    Optional<TaskRun> findRun(String runId);

    /** 按状态列出运行，供进程重启后的恢复扫描。 */
    List<TaskRun> findByStatus(TaskRunStatus status);

    /**
     * 查找存在过期 STARTED 预留的运行。默认实现保持内存/测试存储兼容，
     * JDBC 实现使用单条关联查询，供进程崩溃后的租约收敛扫描。
     */
    default List<String> findRunIdsWithStartedAttemptsBefore(java.time.Instant cutoff) {
        return findByStatus(TaskRunStatus.RUNNING).stream()
                .filter(run -> findAttempts(run.runId()).stream()
                        .anyMatch(attempt -> attempt.outcome() == TaskAttempt.Outcome.STARTED
                                && !attempt.occurredAt().isAfter(cutoff)))
                .map(TaskRun::runId)
                .toList();
    }

    void saveStep(TaskStep step);

    void updateStep(TaskStep step);

    Optional<TaskStep> findStep(String stepId);

    List<TaskStep> findSteps(String runId);

    void saveAttempt(TaskAttempt attempt);

    /** Finalizes a durable pre-dispatch STARTED reservation with the actual provider result. */
    default void updateAttempt(TaskAttempt attempt) {
        throw new UnsupportedOperationException("Attempt updates are not supported");
    }

    List<TaskAttempt> findAttempts(String runId);

    /** 按所有者列出运行（用户时间线视图），默认不支持。 */
    default List<TaskRun> findByOwner(String ownerActorId, int limit) {
        return List.of();
    }

    /** 保留期清理：删除上下文已过期的运行（级联步骤与尝试），返回删除数量。 */
    default int purgeExpiredRuns(java.time.Instant now) {
        return 0;
    }
}
