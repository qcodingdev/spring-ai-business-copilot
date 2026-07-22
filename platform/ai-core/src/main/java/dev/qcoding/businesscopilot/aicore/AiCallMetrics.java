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

    public AiCallMetrics(MeterRegistry registry, AiModelProperties properties) {
        this.registry = registry;
        this.provider = properties.providerName();
        this.model = properties.modelName();
    }

    public void record(String type, String operation, String status, long latencyNanos) {
        if (registry == null) return;
        Tags tags = baseTags(type, operation).and("status", status);
        registry.counter("business.copilot.ai.calls", tags).increment();
        Timer.builder("business.copilot.ai.latency")
                .description("AI 外部调用耗时")
                .tags(tags)
                .register(registry)
                .record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    public void recordTokens(String operation, Integer inputTokens, Integer outputTokens) {
        if (registry == null) return;
        if (inputTokens != null && inputTokens > 0) {
            registry.counter("business.copilot.ai.tokens", baseTags("chat", operation).and("direction", "input"))
                    .increment(inputTokens);
        }
        if (outputTokens != null && outputTokens > 0) {
            registry.counter("business.copilot.ai.tokens", baseTags("chat", operation).and("direction", "output"))
                    .increment(outputTokens);
        }
    }

    private Tags baseTags(String type, String operation) {
        return Tags.of("type", type, "operation", operation, "provider", provider, "model", model);
    }
}
