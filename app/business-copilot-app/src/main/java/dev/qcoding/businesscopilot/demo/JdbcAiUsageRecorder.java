package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.aicore.AiUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/** 持久化低基数 AI 用量聚合；不接收也不保存用户输入、Prompt 或业务正文。 */
@Component
public class JdbcAiUsageRecorder implements AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcAiUsageRecorder.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private final JdbcTemplate jdbcTemplate;
    private final RuntimeModeProperties runtimeModeProperties;
    private final PublicDemoProperties properties;
    private final PublicDemoQuotaService quotaService;

    public JdbcAiUsageRecorder(JdbcTemplate jdbcTemplate,
                               RuntimeModeProperties runtimeModeProperties,
                               PublicDemoProperties properties,
                               PublicDemoQuotaService quotaService) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeModeProperties = runtimeModeProperties;
        this.properties = properties;
        this.quotaService = quotaService;
    }

    @Override
    public void beforeExternalCall(String provider, String model, String type, String operation) {
        if (runtimeModeProperties.mode() == RuntimeMode.PUBLIC_DEMO) {
            quotaService.reserveExternalModelCall();
        }
    }

    @Override
    public void recordCall(String provider, String model, String type, String operation,
                           String status, long latencyNanos) {
        boolean success = "success".equals(status);
        try {
            jdbcTemplate.update("""
                INSERT INTO ai_usage_daily (
                    usage_date, provider_name, model_name, call_type, operation,
                    calls, successes, failures, total_latency_ms, updated_at
                ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                ON CONFLICT (usage_date, provider_name, model_name, call_type, operation)
                DO UPDATE SET
                    calls = ai_usage_daily.calls + 1,
                    successes = ai_usage_daily.successes + EXCLUDED.successes,
                    failures = ai_usage_daily.failures + EXCLUDED.failures,
                    total_latency_ms = ai_usage_daily.total_latency_ms + EXCLUDED.total_latency_ms,
                    updated_at = EXCLUDED.updated_at
                """, Date.valueOf(today()), safe(provider), safe(model), safe(type), safe(operation),
                success ? 1 : 0, success ? 0 : 1,
                    TimeUnit.NANOSECONDS.toMillis(Math.max(0, latencyNanos)), Timestamp.from(Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("AI 调用用量聚合写入失败，未记录任何业务输入", ex);
        }
    }

    @Override
    public void recordTokens(String provider, String model, String operation,
                             Integer inputTokens, Integer outputTokens) {
        long input = positive(inputTokens);
        long output = positive(outputTokens);
        BigDecimal cost = estimatedCost(input, output);
        try {
            jdbcTemplate.update("""
                INSERT INTO ai_usage_daily (
                    usage_date, provider_name, model_name, call_type, operation,
                    input_tokens, output_tokens, estimated_cost, updated_at
                ) VALUES (?, ?, ?, 'chat', ?, ?, ?, ?, ?)
                ON CONFLICT (usage_date, provider_name, model_name, call_type, operation)
                DO UPDATE SET
                    input_tokens = ai_usage_daily.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = ai_usage_daily.output_tokens + EXCLUDED.output_tokens,
                    estimated_cost = CASE
                        WHEN ai_usage_daily.estimated_cost IS NULL OR EXCLUDED.estimated_cost IS NULL
                        THEN NULL
                        ELSE ai_usage_daily.estimated_cost + EXCLUDED.estimated_cost
                    END,
                    updated_at = EXCLUDED.updated_at
                """, Date.valueOf(today()), safe(provider), safe(model), safe(operation),
                    input, output, cost, Timestamp.from(Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("AI Token 聚合写入失败，未记录任何业务输入", ex);
        }
    }

    private BigDecimal estimatedCost(long input, long output) {
        BigDecimal inputPrice = properties.inputTokenPricePerMillion();
        BigDecimal outputPrice = properties.outputTokenPricePerMillion();
        if (inputPrice == null || outputPrice == null) return null;
        return inputPrice.multiply(BigDecimal.valueOf(input))
                .add(outputPrice.multiply(BigDecimal.valueOf(output)))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
    }

    private LocalDate today() {
        return LocalDate.now(properties.zoneId());
    }

    private long positive(Integer value) {
        return value == null ? 0L : Math.max(0, value.longValue());
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
