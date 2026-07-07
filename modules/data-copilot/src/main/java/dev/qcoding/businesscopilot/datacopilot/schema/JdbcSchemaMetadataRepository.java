package dev.qcoding.businesscopilot.datacopilot.schema;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JDBC implementation that reads PostgreSQL information_schema for whitelisted tables.
 *
 * <p>基于 Spring JDBC 的 schema 元数据读取。只读取白名单内的表结构，
 * 不暴露数据库连接信息或非白名单表。</p>
 */
@Repository
public class JdbcSchemaMetadataRepository implements SchemaMetadataRepository {

    private static final String FIND_TABLES_SQL = """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

    private static final String FIND_COLUMNS_SQL = """
            SELECT column_name, data_type, is_nullable,
                   col_description(t.oid, a.attnum) as description
            FROM information_schema.columns c
            LEFT JOIN pg_namespace n ON n.nspname = c.table_schema
            LEFT JOIN pg_class t ON t.relname = c.table_name AND t.relnamespace = n.oid
            LEFT JOIN pg_attribute a ON a.attrelid = t.oid
                AND a.attname = c.column_name
                AND a.attnum > 0
                AND NOT a.attisdropped
            WHERE c.table_schema = 'public' AND c.table_name = ?
            ORDER BY c.ordinal_position
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DataCopilotSchemaProperties properties;

    public JdbcSchemaMetadataRepository(JdbcTemplate jdbcTemplate, DataCopilotSchemaProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public List<String> findQueryableTableNames() {
        List<String> allTables = jdbcTemplate.queryForList(FIND_TABLES_SQL, String.class);
        // 只返回白名单内且实际存在的表
        return allTables.stream()
                .filter(t -> properties.queryableTables().contains(t.toLowerCase()))
                .toList();
    }

    @Override
    public List<ColumnSchema> findColumns(String tableName) {
        return jdbcTemplate.query(FIND_COLUMNS_SQL, (rs, rowNum) -> {
            String colName = rs.getString("column_name");
            String colType = rs.getString("data_type");
            boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
            // pg_catalog.col_description may return null; fall back to configured description
            String pgDesc = rs.getString("description");
            String description = resolveDescription(tableName, colName, pgDesc);
            boolean sensitive = isSensitiveColumn(tableName, colName);
            String maskingStrategy = sensitive ? properties.sensitiveColumns().get(tableName.toLowerCase() + "." + colName.toLowerCase()) : null;
            return new ColumnSchema(colName, colType, nullable, description, sensitive, maskingStrategy);
        }, tableName);
    }

    @Override
    public boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    private String resolveDescription(String tableName, String colName, String pgDesc) {
        String key = tableName.toLowerCase() + "." + colName.toLowerCase();
        String configured = properties.columnDescriptions().get(key);
        if (configured != null && !configured.isBlank()) return configured;
        if (pgDesc != null && !pgDesc.isBlank()) return pgDesc;
        return colName; // 默认用列名作为描述
    }

    private boolean isSensitiveColumn(String tableName, String colName) {
        String key = tableName.toLowerCase() + "." + colName.toLowerCase();
        return properties.sensitiveColumns().containsKey(key);
    }
}
