package dev.qcoding.businesscopilot.datacopilot.enterprise;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Data 企业治理预算；用于在执行前拒绝明显超出预算的查询计划。 */
@ConfigurationProperties(prefix = "business-copilot.data-copilot.enterprise")
public record DataEnterpriseProperties(long maxEstimatedRows, boolean blockHighRiskPlan,
                                       Duration resultRetention) {

    public DataEnterpriseProperties(long maxEstimatedRows, boolean blockHighRiskPlan) {
        this(maxEstimatedRows, blockHighRiskPlan, Duration.ofHours(24));
    }

    public DataEnterpriseProperties {
        if (maxEstimatedRows <= 0) {
            maxEstimatedRows = 100_000;
        }
        if (resultRetention == null || resultRetention.isZero() || resultRetention.isNegative()) {
            resultRetention = Duration.ofHours(24);
        }
    }
}
