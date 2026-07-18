package dev.qcoding.businesscopilot.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bounded retention defaults for all module-owned audit tables. */
@ConfigurationProperties(prefix = "business-copilot.audit.retention")
public record AuditRetentionProperties(Duration anonymizeAfter, Duration deleteAfter) {

    public AuditRetentionProperties {
        if (anonymizeAfter == null || anonymizeAfter.isNegative() || anonymizeAfter.isZero()) {
            anonymizeAfter = Duration.ofDays(7);
        }
        if (deleteAfter == null || deleteAfter.compareTo(anonymizeAfter) <= 0) {
            deleteAfter = Duration.ofDays(30);
        }
    }
}
