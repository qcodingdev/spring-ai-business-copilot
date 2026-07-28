package dev.qcoding.businesscopilot.datacopilot.enterprise;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Data 企业治理预算；用于在执行前拒绝明显超出预算的查询计划。 */
@ConfigurationProperties(prefix = "business-copilot.data-copilot.enterprise")
public record DataEnterpriseProperties(long maxEstimatedRows, boolean blockHighRiskPlan) {

    public DataEnterpriseProperties {
        if (maxEstimatedRows <= 0) {
            maxEstimatedRows = 100_000;
        }
    }
}
