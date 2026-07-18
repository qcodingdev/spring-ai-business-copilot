package dev.qcoding.businesscopilot.aicore;

/** Model output paired with metadata returned by the same provider call. */
public record AiInvocationResult<T>(T content, AiInvocationMetadata metadata) {
}
