package dev.qcoding.businesscopilot.datacopilot.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for read-only query execution.
 *
 * <p>只读查询执行配置。除 statement timeout 外，同时限制 JDBC 最大行数、
 * fetch size、结果列数和结果字节数，避免模型生成查询造成无界资源消耗。
 * 放在 data-copilot 配置下。</p>
 *
 * @param queryTimeoutSeconds statement-level query timeout in seconds
 * @param maxRows             maximum rows returned; surplus rows are dropped and the result flagged truncated
 * @param fetchSize           JDBC driver fetch size
 * @param maxColumns          maximum result columns
 * @param maxResultBytes      approximate maximum serialized result bytes
 */
@ConfigurationProperties(prefix = "business-copilot.data-copilot.query")
public record QueryExecutionProperties(
        int queryTimeoutSeconds,
        int maxRows,
        int fetchSize,
        int maxColumns,
        int maxResultBytes) {

    public QueryExecutionProperties(int queryTimeoutSeconds, int maxRows) {
        this(queryTimeoutSeconds, maxRows, 0, 0, 0);
    }

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
        if (fetchSize <= 0) {
            fetchSize = 50;
        }
        if (maxColumns <= 0) {
            maxColumns = 50;
        }
        if (maxResultBytes <= 0) {
            maxResultBytes = 1024 * 1024;
        }
    }
}
