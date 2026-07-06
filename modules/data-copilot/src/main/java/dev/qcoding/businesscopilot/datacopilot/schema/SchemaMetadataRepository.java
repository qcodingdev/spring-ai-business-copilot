package dev.qcoding.businesscopilot.datacopilot.schema;

import java.util.List;

/**
 * Reads PostgreSQL metadata to build table and column schemas.
 *
 * <p>从 PostgreSQL metadata 读取表白名单内的表结构信息。
 * 不读取 query_audit_logs 等非白名单表。</p>
 */
public interface SchemaMetadataRepository {

    /** List all tables in the whitelist that actually exist in the database. */
    List<String> findQueryableTableNames();

    /** Read column metadata for a given table. */
    List<ColumnSchema> findColumns(String tableName);

    /** Check whether a table exists in the database. */
    boolean tableExists(String tableName);
}
