package dev.qcoding.businesscopilot.aicore;

/** Provider-neutral metadata captured from one real chat-model invocation. */
public record AiInvocationMetadata(
        String providerName,
        String modelName,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason,
        long latencyMs) {
}
