package dev.qcoding.businesscopilot.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

/** 长期公网体验站的额度、保留和不可逆摘要配置。 */
@ConfigurationProperties(prefix = "business-copilot.public-demo")
public record PublicDemoProperties(
        int clientDailyOperations,
        int globalDailyModelCalls,
        int maxConcurrentExecutions,
        String fingerprintSecret,
        String timezone,
        Duration temporaryDataRetention,
        Duration operationLogRetention,
        Duration usageRetention,
        BigDecimal inputTokenPricePerMillion,
        BigDecimal outputTokenPricePerMillion) {

    public PublicDemoProperties {
        if (clientDailyOperations <= 0) clientDailyOperations = 20;
        if (globalDailyModelCalls <= 0) globalDailyModelCalls = 500;
        if (maxConcurrentExecutions <= 0) maxConcurrentExecutions = 4;
        if (fingerprintSecret == null || fingerprintSecret.isBlank()) {
            fingerprintSecret = "development-only-change-me";
        }
        if (timezone == null || timezone.isBlank()) timezone = "Asia/Shanghai";
        if (temporaryDataRetention == null) temporaryDataRetention = Duration.ofHours(24);
        if (operationLogRetention == null) operationLogRetention = Duration.ofDays(7);
        if (usageRetention == null) usageRetention = Duration.ofDays(30);
    }

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }
}
