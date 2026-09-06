package dev.qcoding.businesscopilot.taskruntime;

import java.time.Duration;
import java.time.Instant;

/**
 * 单次任务的累计预算（RUN-02）。
 *
 * <p>限制模型次数、工具次数、Token 和总耗时；所有重试（语言重试、结构修复、工具循环）
 * 都必须计入同一预算。任一维度超出即视为耗尽，任务停止或转人工，不允许无限重试。</p>
 *
 * @param maxModelCalls 最大模型调用次数；null 表示不限制
 * @param maxToolCalls  最大工具调用次数；null 表示不限制
 * @param maxTokens     最大累计 Token（输入+输出）；null 表示不限制
 * @param maxDuration   最大总耗时；null 表示不限制
 */
public record TaskRunBudget(Integer maxModelCalls, Integer maxToolCalls,
                            Integer maxTokens, Duration maxDuration) {

    public TaskRunBudget {
        if (maxModelCalls != null && maxModelCalls < 0) {
            throw new IllegalArgumentException("maxModelCalls must not be negative");
        }
        if (maxToolCalls != null && maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls must not be negative");
        }
        if (maxTokens != null && maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must not be negative");
        }
        if (maxDuration != null && maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must not be negative");
        }
    }

    /** 不限制任何维度的预算（用于不需要预算的轻量调用）。 */
    public static TaskRunBudget unlimited() {
        return new TaskRunBudget(null, null, null, null);
    }

    /** 追踪一次运行内的预算消耗；线程不安全，单次任务执行内使用。 */
    public static final class Tracker {

        private final TaskRunBudget budget;
        private final Instant startedAt;
        private int modelCalls;
        private int toolCalls;
        private long tokens;
        private boolean unknownUsage;
        private Duration totalWait = Duration.ZERO;

        public Tracker(TaskRunBudget budget, Instant startedAt) {
            this.budget = budget;
            this.startedAt = startedAt;
        }

        public TaskRunBudget budget() {
            return budget;
        }

        public int modelCalls() {
            return modelCalls;
        }

        public int toolCalls() {
            return toolCalls;
        }

        public long tokens() {
            return tokens;
        }

        /** 记录一次模型调用（含所有重试尝试）；超出次数预算时返回 false。 */
        public boolean consumeModelCall() {
            if (budget.maxModelCalls() != null && modelCalls >= budget.maxModelCalls()) {
                return false;
            }
            modelCalls++;
            return true;
        }

        /** 记录一次工具调用；超出次数预算时返回 false。 */
        public boolean consumeToolCall() {
            if (budget.maxToolCalls() != null && toolCalls >= budget.maxToolCalls()) {
                return false;
            }
            toolCalls++;
            return true;
        }

        /** 记录一次尝试的 Token 用量；null（未知）不计入也不报错，但调用方应另行标记未知。 */
        public boolean consumeTokens(Integer inputTokens, Integer outputTokens) {
            unknownUsage |= inputTokens == null || outputTokens == null;
            if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)) {
                throw new IllegalArgumentException("Token usage must not be negative");
            }
            if (inputTokens != null) {
                tokens += inputTokens;
            }
            if (outputTokens != null) {
                tokens += outputTokens;
            }
            return budget.maxTokens() == null || (!unknownUsage && tokens <= budget.maxTokens());
        }

        /** 是否已有任一维度达到或超出上限（以 now 评估时长）。 */
        public boolean exhausted(Instant now) {
            if (budget.maxModelCalls() != null && modelCalls >= budget.maxModelCalls()) {
                return true;
            }
            if (budget.maxToolCalls() != null && toolCalls >= budget.maxToolCalls()) {
                return true;
            }
            if (budget.maxTokens() != null && (unknownUsage || tokens >= budget.maxTokens())) {
                return true;
            }
            return budget.maxDuration() != null
                    && Duration.between(startedAt, now).minus(totalWait).compareTo(budget.maxDuration()) >= 0;
        }

        /** 等待人工确认的时长不计入执行时长预算（等待不占用执行窗口）。 */
        public void excludeWait(Duration waited) {
            if (waited != null && !waited.isNegative()) {
                totalWait = totalWait.plus(waited);
            }
        }
    }
}
