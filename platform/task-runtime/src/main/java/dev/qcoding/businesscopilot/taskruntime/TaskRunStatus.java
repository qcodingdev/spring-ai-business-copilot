package dev.qcoding.businesscopilot.taskruntime;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 任务运行的终态与中间态（RUN-01/执行状态）。
 *
 * <p>可表达运行中、等待确认、成功、失败、取消、预算耗尽和结果未知。
 * 状态转换显式声明：非法转换在 {@link TaskRunService} 中被拒绝，防止恢复或取消路径
 * 意外覆盖已终态的运行。</p>
 */
public enum TaskRunStatus {

    RUNNING,
    WAITING_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,
    OUTCOME_UNKNOWN;

    private static final Map<TaskRunStatus, Set<TaskRunStatus>> ALLOWED = Map.of(
            RUNNING, EnumSet.of(WAITING_CONFIRMATION, SUCCEEDED, FAILED, CANCELLED,
                    BUDGET_EXHAUSTED, OUTCOME_UNKNOWN),
            WAITING_CONFIRMATION, EnumSet.of(RUNNING, FAILED, CANCELLED),
            OUTCOME_UNKNOWN, EnumSet.of(RUNNING),
            SUCCEEDED, EnumSet.noneOf(TaskRunStatus.class),
            FAILED, EnumSet.noneOf(TaskRunStatus.class),
            CANCELLED, EnumSet.noneOf(TaskRunStatus.class),
            BUDGET_EXHAUSTED, EnumSet.noneOf(TaskRunStatus.class));

    /** 是否已经处于不会再回到执行路径的终态。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == BUDGET_EXHAUSTED;
    }

    /** from → to 是否是显式允许的转换。 */
    public boolean canTransitionTo(TaskRunStatus to) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(TaskRunStatus.class)).contains(to);
    }
}
