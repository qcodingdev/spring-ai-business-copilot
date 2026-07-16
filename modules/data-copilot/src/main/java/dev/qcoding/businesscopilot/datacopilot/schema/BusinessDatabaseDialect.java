package dev.qcoding.businesscopilot.datacopilot.schema;

import java.util.Locale;

/** Supported external read-only business database dialects. */
public enum BusinessDatabaseDialect {

    POSTGRESQL(
            "jdbc:postgresql:",
            "org.postgresql.Driver",
            "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY",
            """
                    SELECT column_name, data_type, is_nullable,
                           col_description(t.oid, a.attnum) AS description
                    FROM information_schema.columns c
                    LEFT JOIN pg_namespace n ON n.nspname = c.table_schema
                    LEFT JOIN pg_class t ON t.relname = c.table_name AND t.relnamespace = n.oid
                    LEFT JOIN pg_attribute a ON a.attrelid = t.oid
                        AND a.attname = c.column_name
                        AND a.attnum > 0
                        AND NOT a.attisdropped
                    WHERE c.table_schema = ? AND c.table_name = ?
                    ORDER BY c.ordinal_position
                    """),

    MYSQL(
            "jdbc:mysql:",
            "com.mysql.cj.jdbc.Driver",
            "SET SESSION TRANSACTION READ ONLY",
            """
                    SELECT column_name, data_type, is_nullable,
                           NULLIF(column_comment, '') AS description
                    FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = ?
                    ORDER BY ordinal_position
                    """);

    private final String jdbcPrefix;
    private final String driverClassName;
    private final String connectionInitSql;
    private final String columnsSql;

    BusinessDatabaseDialect(String jdbcPrefix, String driverClassName,
                            String connectionInitSql, String columnsSql) {
        this.jdbcPrefix = jdbcPrefix;
        this.driverClassName = driverClassName;
        this.connectionInitSql = connectionInitSql;
        this.columnsSql = columnsSql;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public String connectionInitSql() {
        return connectionInitSql;
    }

    String columnsSql() {
        return columnsSql;
    }

    public boolean accepts(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith(jdbcPrefix);
    }

    public static BusinessDatabaseDialect resolve(String configuredDialect, String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("Business query datasource URL must not be blank");
        }
        BusinessDatabaseDialect detected = detect(jdbcUrl);
        if (configuredDialect == null || configuredDialect.isBlank()
                || "auto".equalsIgnoreCase(configuredDialect)) {
            return detected;
        }
        BusinessDatabaseDialect configured;
        try {
            configured = valueOf(configuredDialect.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported business query database dialect: " + configuredDialect, ex);
        }
        if (configured != detected) {
            throw new IllegalArgumentException(
                    "Business query database dialect does not match JDBC URL");
        }
        return configured;
    }

    private static BusinessDatabaseDialect detect(String jdbcUrl) {
        for (BusinessDatabaseDialect dialect : values()) {
            if (dialect.accepts(jdbcUrl)) {
                return dialect;
            }
        }
        throw new IllegalArgumentException(
                "Only PostgreSQL and MySQL business query JDBC URLs are supported");
    }
}
