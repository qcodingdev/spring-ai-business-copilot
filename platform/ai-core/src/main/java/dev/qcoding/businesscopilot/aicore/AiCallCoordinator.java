package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 五个 Copilot 共用的 AI 调用入口，负责调用编号、中文链路日志、并发隔离和熔断。
 *
 * <p>operation 必须是代码内固定值，不能放入用户输入，避免日志注入和指标高基数。</p>
 */
public final class AiCallCoordinator {

    public static final String AI_CALL_ID_MDC_KEY = "aiCallId";
    public static final String AI_OPERATION_MDC_KEY = "aiOperation";
    private static final Pattern SAFE_OPERATION = Pattern.compile("[a-z0-9._-]{3,64}");
    private static final Logger log = LoggerFactory.getLogger(AiCallCoordinator.class);

    private final Semaphore permits;
    private final AiResilienceProperties properties;
    private final AiCallMetrics metrics;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public AiCallCoordinator(AiResilienceProperties properties, AiCallMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.permits = new Semaphore(properties.maxConcurrentCalls(), true);
    }

    public <T> T execute(String type, String operation, Supplier<T> supplier) {
        String safeType = "embedding".equals(type) ? "embedding" : "chat";
        String safeOperation = normalizeOperation(operation);
        String callId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String previousCallId = MDC.get(AI_CALL_ID_MDC_KEY);
        String previousOperation = MDC.get(AI_OPERATION_MDC_KEY);
        MDC.put(AI_CALL_ID_MDC_KEY, callId);
        MDC.put(AI_OPERATION_MDC_KEY, safeOperation);
        long startedAt = System.nanoTime();
        boolean acquired = false;
        try {
            log.debug("AI 调用开始：类型={}，操作={}", safeType, safeOperation);
            acquired = permits.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                metrics.record(safeType, safeOperation, "busy", System.nanoTime() - startedAt);
                log.warn("AI 调用并发已满，未在等待时间内取得执行许可：类型={}，操作={}", safeType, safeOperation);
                throw new BusinessException(ErrorCode.AI_MODEL_ERROR, "AI 服务当前繁忙，请稍后重试。");
            }
            metrics.beforeExternalCall(safeType, safeOperation);
            T result = circuitBreaker(safeType).executeSupplier(supplier);
            long latency = System.nanoTime() - startedAt;
            metrics.record(safeType, safeOperation, "success", latency);
            log.info("AI 调用完成：类型={}，操作={}，耗时毫秒={}",
                    safeType, safeOperation, TimeUnit.NANOSECONDS.toMillis(latency));
            return result;
        } catch (CallNotPermittedException ex) {
            metrics.record(safeType, safeOperation, "circuit_open", System.nanoTime() - startedAt);
            log.warn("AI 调用已被熔断器拒绝：类型={}，操作={}", safeType, safeOperation);
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR, "AI 服务连续失败，保护机制已暂时停止调用，请稍后重试。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            metrics.record(safeType, safeOperation, "interrupted", System.nanoTime() - startedAt);
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR, "AI 调用等待被中断，请稍后重试。", ex);
        } catch (BusinessException ex) {
            if (acquired) metrics.record(safeType, safeOperation, "failure", System.nanoTime() - startedAt);
            throw ex;
        } catch (RuntimeException ex) {
            metrics.record(safeType, safeOperation, "failure", System.nanoTime() - startedAt);
            // 协调器只记录可检索的链路摘要；完整异常由 Chat/Embedding 封装层记录一次，避免重复堆栈刷屏。
            log.error("AI 调用失败：类型={}，操作={}，异常类型={}",
                    safeType, safeOperation, ex.getClass().getSimpleName());
            throw ex;
        } finally {
            if (acquired) permits.release();
            restoreMdc(AI_CALL_ID_MDC_KEY, previousCallId);
            restoreMdc(AI_OPERATION_MDC_KEY, previousOperation);
        }
    }

    public void recordTokens(String operation, Integer inputTokens, Integer outputTokens) {
        metrics.recordTokens(normalizeOperation(operation), inputTokens, outputTokens);
    }

    /** 管理台只读诊断，不暴露调用参数或供应商异常正文。 */
    public Diagnostics diagnostics() {
        Map<String, String> states = new java.util.TreeMap<>();
        circuitBreakers.forEach((type, breaker) ->
                states.put(type, breaker.getState().name()));
        return new Diagnostics(
                properties.maxConcurrentCalls(),
                permits.availablePermits(),
                Map.copyOf(states));
    }

    CircuitBreaker circuitBreaker(String type) {
        return circuitBreakers.computeIfAbsent(type, this::createCircuitBreaker);
    }

    private CircuitBreaker createCircuitBreaker(String type) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.openStateDuration())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("ai-" + type, config);
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("AI 熔断状态变化：类型={}，原状态={}，新状态={}", type,
                        event.getStateTransition().getFromState(), event.getStateTransition().getToState()));
        return breaker;
    }

    private String normalizeOperation(String operation) {
        if (operation == null) return "unknown";
        String normalized = operation.trim().toLowerCase(java.util.Locale.ROOT);
        return SAFE_OPERATION.matcher(normalized).matches() ? normalized : "unknown";
    }

    private void restoreMdc(String key, String previousValue) {
        if (previousValue == null) MDC.remove(key); else MDC.put(key, previousValue);
    }

    public record Diagnostics(
            int maxConcurrentCalls,
            int availablePermits,
            Map<String, String> circuitStates) {
    }
}
