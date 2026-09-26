package dev.qcoding.businesscopilot.aicore;

import java.util.List;

/**
 * Provider-neutral metadata captured from one real chat-model invocation.
 *
 * <p>当一次业务生成包含多次模型尝试（例如语言重试）时，用
 * {@link #cumulative(List, long)} 合并为累计元数据：Token 只要有一次尝试未知就保持未知（null），
 * 不把缺失用量记为零。</p>
 */
public record AiInvocationMetadata(
        String providerName,
        String modelName,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason,
        long latencyMs) {

    /** 把同一业务生成的多次尝试合并为累计用量；标识字段取最后一次成功尝试。 */
    public static AiInvocationMetadata cumulative(List<AiInvocationMetadata> attempts, long totalLatencyMs) {
        if (attempts == null || attempts.isEmpty()) {
            throw new IllegalArgumentException("attempts must not be empty");
        }
        AiInvocationMetadata last = attempts.get(attempts.size() - 1);
        Integer inputTokens = 0;
        Integer outputTokens = 0;
        for (AiInvocationMetadata attempt : attempts) {
            // 任一次尝试未返回用量时，累计值保持未知，不记为零。
            if (attempt.inputTokens() == null) inputTokens = null;
            else if (inputTokens != null) inputTokens += attempt.inputTokens();
            if (attempt.outputTokens() == null) outputTokens = null;
            else if (outputTokens != null) outputTokens += attempt.outputTokens();
        }
        return new AiInvocationMetadata(
                last.providerName(), last.modelName(), last.providerRequestId(),
                inputTokens, outputTokens, last.finishReason(), totalLatencyMs);
    }
}
