package dev.qcoding.businesscopilot.taskruntime;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次业务任务运行（RUN-01 统一运行标识）。
 *
 * <p>runId 关联操作者、业务对象、模型调用（{@link TaskAttempt}）和步骤（{@link TaskStep}）。
 * 跨模块的运行关联只用于追溯，不扩大权限：取消和恢复仍按对象归属校验。</p>
 */
public record TaskRun(
        String runId,
        String module,
        String refType,
        String refId,
        String ownerActorId,
        TaskRunStatus status,
        TaskRunBudget budget,
        ContextManifest contextManifest,
        Instant startedAt,
        Instant endedAt,
        FailureCategory failureCategory,
        String stopReason,
        String confirmedByActorId) {

    public TaskRun {
        if (runId == null || runId.isBlank()) {
            runId = UUID.randomUUID().toString();
        }
    }

    public boolean terminal() {
        return status != null && status.terminal();
    }
}
