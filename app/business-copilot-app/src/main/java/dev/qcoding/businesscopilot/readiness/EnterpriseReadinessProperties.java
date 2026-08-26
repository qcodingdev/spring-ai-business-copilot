package dev.qcoding.businesscopilot.readiness;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bounded windows used to calculate and retain enterprise-readiness evidence. */
@ConfigurationProperties(prefix = "business-copilot.enterprise-readiness")
public record EnterpriseReadinessProperties(
        String applicationVersion,
        Duration snapshotValidity,
        Duration snapshotRetention,
        Duration staleOperationAfter,
        Duration expiredResultGrace,
        Duration failedRunLookback) {

    public EnterpriseReadinessProperties {
        if (applicationVersion == null || applicationVersion.isBlank()) {
            applicationVersion = "2.4.0";
        } else {
            applicationVersion = applicationVersion.trim();
        }
        if (applicationVersion.length() > 64) {
            throw new IllegalArgumentException("企业运行就绪应用版本不能超过 64 个字符");
        }
        snapshotValidity = validatedOrDefault(
                "snapshot-validity", snapshotValidity, Duration.ofHours(24));
        snapshotRetention = validatedOrDefault(
                "snapshot-retention", snapshotRetention, Duration.ofDays(90));
        if (snapshotRetention.compareTo(snapshotValidity) <= 0) {
            throw new IllegalArgumentException(
                    "snapshot-retention 必须大于 snapshot-validity");
        }
        staleOperationAfter = validatedOrDefault(
                "stale-operation-after", staleOperationAfter, Duration.ofMinutes(15));
        expiredResultGrace = validatedOrDefault(
                "expired-result-grace", expiredResultGrace, Duration.ofHours(1));
        failedRunLookback = validatedOrDefault(
                "failed-run-lookback", failedRunLookback, Duration.ofDays(7));
    }

    private static Duration validatedOrDefault(
            String propertyName, Duration value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " 必须大于 0");
        }
        return value;
    }
}
