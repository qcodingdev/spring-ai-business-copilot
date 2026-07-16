package dev.qcoding.businesscopilot.datacopilot.schema;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * JDBC implementation that reads PostgreSQL information_schema for whitelisted tables.
 *
 * <p>基于 Spring JDBC 的 schema 元数据读取。只读取白名单内的表结构，
 * 不暴露数据库连接信息或非白名单表。</p>
 */
public class JdbcSchemaMetadataRepository implements SchemaMetadataRepository {

    private static final String FIND_TABLES_SQL = """
            SELECT table_schema, table_name FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
            ORDER BY table_schema, table_name
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DataCopilotSchemaProperties properties;
    private final BusinessDatabaseDialect dialect;

    public JdbcSchemaMetadataRepository(JdbcTemplate jdbcTemplate, DataCopilotSchemaProperties properties) {
        this(jdbcTemplate, properties, BusinessDatabaseDialect.POSTGRESQL);
    }

    public JdbcSchemaMetadataRepository(JdbcTemplate jdbcTemplate,
                                        DataCopilotSchemaProperties properties,
                                        BusinessDatabaseDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.dialect = dialect;
    }

    @Override
    public List<String> findQueryableTableNames() {
        List<String> allTables = jdbcTemplate.query(
                FIND_TABLES_SQL,
                (rs, rowNum) -> new QualifiedTableName(
                        rs.getString("table_schema"),
                        rs.getString("table_name")).canonicalName());
        // 只返回白名单内且实际存在的表
        return allTables.stream()
                .filter(properties.queryableTables()::contains)
                .toList();
    }

    @Override
    public List<ColumnSchema> findColumns(String tableName) {
        QualifiedTableName qualifiedTable = QualifiedTableName.parse(tableName);
        return jdbcTemplate.query(dialect.columnsSql(), (rs, rowNum) -> {
            String colName = rs.getString("column_name");
            String colType = rs.getString("data_type");
            boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
            String databaseDescription = rs.getString("description");
            String description = resolveDescription(qualifiedTable, colName, databaseDescription);
            String maskingStrategy = resolveConfiguredColumnValue(
                    properties.sensitiveColumns(), qualifiedTable, colName);
            boolean sensitive = maskingStrategy != null;
            return new ColumnSchema(colName, colType, nullable, description, sensitive, maskingStrategy);
        }, qualifiedTable.schema(), qualifiedTable.table());
    }

    @Override
    public boolean tableExists(String tableName) {
        QualifiedTableName qualifiedTable = QualifiedTableName.parse(tableName);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, qualifiedTable.schema(), qualifiedTable.table());
        return count != null && count > 0;
    }

    private String resolveDescription(QualifiedTableName tableName, String colName, String pgDesc) {
        String configured = resolveConfiguredColumnValue(
                properties.columnDescriptions(), tableName, colName);
        if (configured != null && !configured.isBlank()) return configured;
        if (pgDesc != null && !pgDesc.isBlank()) return pgDesc;
        return colName; // 默认用列名作为描述
    }

    private String resolveConfiguredColumnValue(java.util.Map<String, String> values,
                                                QualifiedTableName tableName,
                                                String colName) {
        String normalizedColumn = colName.toLowerCase();
        String qualifiedKey = tableName.canonicalName() + "." + normalizedColumn;
        String configured = values.get(qualifiedKey);
        if (configured != null) {
            return configured;
        }
        // Backward-compatible fallback for existing customer descriptions.
        return values.get(tableName.table() + "." + normalizedColumn);
    }
}
