package dev.qcoding.businesscopilot.datacopilot.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for read-only query execution.
 *
 * <p>只读查询执行配置。queryTimeoutSeconds 控制单次查询超时，
 * maxRows 控制最大返回行数（超出时截断并标记 truncated=true）。
 * 放在 data-copilot 配置下。</p>
 *
 * @param queryTimeoutSeconds statement-level query timeout in seconds
 * @param maxRows             maximum rows returned; surplus rows are dropped and the result flagged truncated
 */
@ConfigurationProperties(prefix = "business-copilot.data-copilot.query")
public record QueryExecutionProperties(
        int queryTimeoutSeconds,
        int maxRows) {

    /** Defaults aligned with the v1 execution boundary. */
    public QueryExecutionProperties {
        if (queryTimeoutSeconds <= 0) {
            // 默认 30 秒超时
            queryTimeoutSeconds = 30;
        }
        if (maxRows <= 0) {
            // 默认最多 100 行
            maxRows = 100;
        }
    }
}
