package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCallCoordinatorTest {

    @Test
    void recordsLowCardinalityMetricsAndRestoresMdc() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiModelProperties model = new AiModelProperties("test-model", "test-provider", false, 1000);
        AiCallCoordinator coordinator = new AiCallCoordinator(defaults(), new AiCallMetrics(registry, model));
        MDC.put(AiCallCoordinator.AI_CALL_ID_MDC_KEY, "parent-call");

        assertThat(coordinator.execute("chat", "data.sql-generation", () -> "ok")).isEqualTo("ok");

        assertThat(MDC.get(AiCallCoordinator.AI_CALL_ID_MDC_KEY)).isEqualTo("parent-call");
        assertThat(MDC.get(AiCallCoordinator.AI_OPERATION_MDC_KEY)).isNull();
        assertThat(registry.get("business.copilot.ai.calls")
                .tags("type", "chat", "operation", "data.sql-generation", "status", "success")
                .counter().count()).isEqualTo(1.0d);
        MDC.clear();
    }

    @Test
    void opensCircuitAfterConfiguredFailureRate() {
        AiResilienceProperties properties = new AiResilienceProperties(
                2, Duration.ZERO, 2, 2, 50, Duration.ofSeconds(30));
        AiCallCoordinator coordinator = new AiCallCoordinator(
                properties, new AiCallMetrics(null,
                new AiModelProperties("test-model", "test-provider", false, 1000)));

        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> coordinator.execute("chat", "support.reply-draft",
                    () -> { throw new IllegalStateException("provider unavailable"); }))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(coordinator.circuitBreaker("chat").getState().name()).isEqualTo("OPEN");
        assertThatThrownBy(() -> coordinator.execute("chat", "support.reply-draft", () -> "never"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("保护机制");
    }

    @Test
    void recordsExplicitEmbeddingProviderAndModel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<String> recordedProvider = new AtomicReference<>();
        AtomicReference<String> recordedModel = new AtomicReference<>();
        AiUsageRecorder recorder = new AiUsageRecorder() {
            @Override
            public void recordCall(String provider, String model, String type, String operation,
                                   String status, long latencyNanos) {
                recordedProvider.set(provider);
                recordedModel.set(model);
            }
        };
        AiModelProperties chat = new AiModelProperties("chat-model", "chat-provider", false, 1000);
        AiCallCoordinator coordinator = new AiCallCoordinator(defaults(),
                new AiCallMetrics(registry, chat, recorder));

        assertThat(coordinator.execute("embedding", "knowledge.embedding",
                "embedding-provider", "embedding-model", () -> "ok")).isEqualTo("ok");

        assertThat(recordedProvider).hasValue("embedding-provider");
        assertThat(recordedModel).hasValue("embedding-model");
        assertThat(registry.get("business.copilot.ai.calls")
                .tags("type", "embedding", "operation", "knowledge.embedding", "status", "success",
                        "provider", "embedding-provider", "model", "embedding-model")
                .counter().count()).isEqualTo(1.0d);
    }

    private AiResilienceProperties defaults() {
        return new AiResilienceProperties(4, Duration.ofMillis(10), 10, 5, 50, Duration.ofSeconds(30));
    }
}
