package dev.qcoding.businesscopilot.datacopilot.query;

/**
 * A column descriptor in a {@link QueryResultTable}.
 *
 * <p>查询结果列描述。包含列名和数据库类型，前端据此决定渲染方式。</p>
 *
 * @param name column name as returned by the JDBC driver
 * @param type SQL type name (e.g. "varchar", "integer", "timestamp")
 */
public record QueryColumn(
        String name,
        String type) {
}
