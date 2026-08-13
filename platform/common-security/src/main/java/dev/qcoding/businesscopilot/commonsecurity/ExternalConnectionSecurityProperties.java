package dev.qcoding.businesscopilot.commonsecurity;

import java.time.Duration;
import java.util.List;

/** 外部企业连接的失败关闭网络边界；域名 allowlist 必须由部署环境明确配置。 */
public record ExternalConnectionSecurityProperties(
        List<String> allowedHosts,
        boolean allowHttp,
        boolean allowPrivateAddresses,
        Duration connectTimeout,
        Duration readTimeout,
        Duration taskTimeout,
        long maxResponseBytes,
        int maxPages,
        int maxItems,
        int maxJsonDepth) {

    public ExternalConnectionSecurityProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(String::toLowerCase)
                .distinct()
                .toList();
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(3));
        readTimeout = positive(readTimeout, Duration.ofSeconds(10));
        taskTimeout = positive(taskTimeout, Duration.ofSeconds(30));
        maxResponseBytes = maxResponseBytes <= 0 ? 2_000_000 : Math.min(maxResponseBytes, 10_000_000);
        maxPages = maxPages <= 0 ? 10 : Math.min(maxPages, 100);
        maxItems = maxItems <= 0 ? 1_000 : Math.min(maxItems, 10_000);
        maxJsonDepth = maxJsonDepth <= 0 ? 32 : Math.min(maxJsonDepth, 100);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
