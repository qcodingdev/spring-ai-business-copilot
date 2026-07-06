package dev.qcoding.businesscopilot.datacopilot.confirmation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for SQL candidate confirmation lifecycle.
 *
 * <p>SQL 候选确认配置。candidateTtlMinutes 控制候选默认有效期，
 * 第一版默认 10 分钟，放在 data-copilot 配置下。</p>
 *
 * @param candidateTtlMinutes how long a candidate remains executable after creation
 */
@ConfigurationProperties(prefix = "business-copilot.data-copilot.confirmation")
public record DataCopilotConfirmationProperties(int candidateTtlMinutes) {

    /** Default TTL aligned with the v1 confirmation flow. */
    public DataCopilotConfirmationProperties {
        if (candidateTtlMinutes <= 0) {
            // 默认 10 分钟过期
            candidateTtlMinutes = 10;
        }
    }
}
