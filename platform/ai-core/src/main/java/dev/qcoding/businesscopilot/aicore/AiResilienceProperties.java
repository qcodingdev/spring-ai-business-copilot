package dev.qcoding.businesscopilot.aicore;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 外部调用的并发与熔断边界。
 *
 * <p>HTTP 超时和瞬时故障重试继续使用 Spring AI 官方配置；这里负责限制并发，
 * 并在提供方持续失败时快速失败，避免五个业务模块一起拖垮应用线程。</p>
 */
@ConfigurationProperties(prefix = "business-copilot.ai-core.resilience")
public record AiResilienceProperties(
        int maxConcurrentCalls,
        Duration acquireTimeout,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        Duration openStateDuration) {

    public AiResilienceProperties {
        if (maxConcurrentCalls <= 0) maxConcurrentCalls = 8;
        if (acquireTimeout == null || acquireTimeout.isNegative()) acquireTimeout = Duration.ofSeconds(2);
        if (slidingWindowSize < 2) slidingWindowSize = 10;
        if (minimumNumberOfCalls < 2 || minimumNumberOfCalls > slidingWindowSize) {
            minimumNumberOfCalls = Math.min(5, slidingWindowSize);
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) failureRateThreshold = 50.0f;
        if (openStateDuration == null || openStateDuration.isNegative() || openStateDuration.isZero()) {
            openStateDuration = Duration.ofSeconds(30);
        }
    }
}
