package dev.qcoding.businesscopilot.taskruntime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 运行内的一个步骤（RUN-04 持久恢复点）。
 *
 * <p>步骤状态持久化后，进程重启可辨别已完成步骤与可重试步骤；
 * evidenceRefs 只保留证据标识（不存敏感全文），summary 为受限长度的可解释摘要。</p>
 */
public record TaskStep(
        String stepId,
        String runId,
        String name,
        TaskStepStatus status,
        int attemptCount,
        FailureCategory failureCategory,
        List<String> evidenceRefs,
        String summary,
        Instant startedAt,
        Instant endedAt) {

    public TaskStep {
        if (stepId == null || stepId.isBlank()) {
            stepId = UUID.randomUUID().toString();
        }
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }

    /** 步骤级状态：完成、失败、跳过（取消/恢复放弃）与待执行。 */
    public enum TaskStepStatus {
        PENDING,
        COMPLETED,
        FAILED,
        SKIPPED
    }
}
