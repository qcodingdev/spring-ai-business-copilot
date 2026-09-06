package dev.qcoding.businesscopilot.taskruntime;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次真实的模型或工具调用尝试（RUN-01/RUN-02）。
 *
 * <p>每次尝试（首次、语言重试、结构修复、工具循环）都单独记录用量；
 * 供应商未返回用量时保持 null（未知），不记为零。outcome=UNKNOWN 表示副作用结果未知，
 * 重试前必须先核对，不能以重试成功掩盖重复执行。</p>
 */
public record TaskAttempt(
        String attemptId,
        String runId,
        String stepId,
        Kind kind,
        String operation,
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs,
        Outcome outcome,
        FailureCategory failureCategory,
        Instant occurredAt) {

    public TaskAttempt {
        if (attemptId == null || attemptId.isBlank()) {
            attemptId = UUID.randomUUID().toString();
        }
    }

    /** 尝试类型：模型调用或工具调用。 */
    public enum Kind {
        MODEL,
        TOOL
    }

    /** 尝试结果：已预留待派发、成功、失败或结果未知（副作用可能已发生）。 */
    public enum Outcome {
        STARTED,
        SUCCESS,
        FAILURE,
        UNKNOWN
    }
}
