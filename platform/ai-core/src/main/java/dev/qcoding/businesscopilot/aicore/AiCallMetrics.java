package dev.qcoding.businesscopilot.aicore;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/** 将 AI 调用结果写入低基数 Micrometer 指标，不记录问题、Prompt 或用户数据。 */
public final class AiCallMetrics {

    private final MeterRegistry registry;
    private final String provider;
    private final String model;
    private final AiUsageRecorder usageRecorder;

    public AiCallMetrics(MeterRegistry registry, AiModelProperties properties) {
        this(registry, properties, AiUsageRecorder.NO_OP);
    }

    public AiCallMetrics(MeterRegistry registry, AiModelProperties properties, AiUsageRecorder usageRecorder) {
        this.registry = registry;
        this.provider = properties.providerName();
        this.model = properties.modelName();
        this.usageRecorder = usageRecorder == null ? AiUsageRecorder.NO_OP : usageRecorder;
    }

    public void beforeExternalCall(String type, String operation) {
        beforeExternalCall(type, operation, provider, model);
    }

    public void beforeExternalCall(String type, String operation, String providerOverride, String modelOverride) {
        usageRecorder.beforeExternalCall(effective(providerOverride, provider), effective(modelOverride, model),
                type, operation);
    }

    public void record(String type, String operation, String status, long latencyNanos) {
        record(type, operation, status, latencyNanos, provider, model);
    }

    public void record(String type, String operation, String status, long latencyNanos,
                       String providerOverride, String modelOverride) {
        String effectiveProvider = effective(providerOverride, provider);
        String effectiveModel = effective(modelOverride, model);
        if (registry != null) {
            Tags tags = baseTags(type, operation, effectiveProvider, effectiveModel).and("status", status);
            registry.counter("business.copilot.ai.calls", tags).increment();
            Timer.builder("business.copilot.ai.latency")
                    .description("AI 外部调用耗时")
                    .tags(tags)
                    .register(registry)
                    .record(latencyNanos, TimeUnit.NANOSECONDS);
        }
        usageRecorder.recordCall(effectiveProvider, effectiveModel, type, operation, status, latencyNanos);
    }

    public void recordTokens(String operation, Integer inputTokens, Integer outputTokens) {
        if (registry != null) {
            if (inputTokens != null && inputTokens > 0) {
                registry.counter("business.copilot.ai.tokens", baseTags("chat", operation).and("direction", "input"))
                        .increment(inputTokens);
            }
            if (outputTokens != null && outputTokens > 0) {
                registry.counter("business.copilot.ai.tokens", baseTags("chat", operation).and("direction", "output"))
                        .increment(outputTokens);
            }
        }
        usageRecorder.recordTokens(provider, model, operation, inputTokens, outputTokens);
    }

    private Tags baseTags(String type, String operation) {
        return baseTags(type, operation, provider, model);
    }

    private Tags baseTags(String type, String operation, String effectiveProvider, String effectiveModel) {
        return Tags.of("type", type, "operation", operation,
                "provider", effectiveProvider, "model", effectiveModel);
    }

    private String effective(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }
}
