package dev.qcoding.businesscopilot.aicore;

/** 可选的持久化 AI 用量观察器；实现方不得记录 Prompt 或用户原始输入。 */
public interface AiUsageRecorder {

    default void beforeExternalCall(String provider, String model, String type, String operation) {
    }

    default void recordCall(String provider, String model, String type, String operation,
                            String status, long latencyNanos) {
    }

    default void recordTokens(String provider, String model, String operation,
                              Integer inputTokens, Integer outputTokens) {
    }

    AiUsageRecorder NO_OP = new AiUsageRecorder() {
    };
}
