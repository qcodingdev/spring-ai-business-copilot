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
        Duration reviewBacklogAfter,
        Duration failedRunLookback) {

    public EnterpriseReadinessProperties {
        if (applicationVersion == null || applicationVersion.isBlank()) {
            applicationVersion = "2.4.0-SNAPSHOT";
        } else {
            applicationVersion = applicationVersion.trim();
        }
        if (applicationVersion.length() > 64) {
            throw new IllegalArgumentException("企业运行就绪应用版本不能超过 64 个字符");
        }
        snapshotValidity = positiveOr(snapshotValidity, Duration.ofHours(24));
        snapshotRetention = positiveOr(snapshotRetention, Duration.ofDays(90));
        if (snapshotRetention.compareTo(snapshotValidity) <= 0) {
            snapshotRetention = snapshotValidity.plus(Duration.ofDays(1));
        }
        staleOperationAfter = positiveOr(staleOperationAfter, Duration.ofMinutes(15));
        expiredResultGrace = positiveOr(expiredResultGrace, Duration.ofHours(1));
        reviewBacklogAfter = positiveOr(reviewBacklogAfter, Duration.ofHours(24));
        failedRunLookback = positiveOr(failedRunLookback, Duration.ofDays(7));
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
