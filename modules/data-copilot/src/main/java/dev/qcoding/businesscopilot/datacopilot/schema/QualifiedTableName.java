package dev.qcoding.businesscopilot.datacopilot.schema;

import java.util.Locale;

/**
 * Canonical PostgreSQL schema/table identifier used by Data Copilot metadata.
 */
record QualifiedTableName(String schema, String table) {

    static QualifiedTableName parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Queryable table name must not be blank");
        }
        String[] parts = value.trim().split("\\.");
        if (parts.length == 1) {
            return new QualifiedTableName("public", normalize(parts[0]));
        }
        if (parts.length == 2) {
            return new QualifiedTableName(normalize(parts[0]), normalize(parts[1]));
        }
        throw new IllegalArgumentException(
                "Queryable table must use schema.table form: " + value);
    }

    String canonicalName() {
        return schema + "." + table;
    }

    private static String normalize(String identifier) {
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z_][a-z0-9_$]*")) {
            throw new IllegalArgumentException(
                    "Queryable table identifier is invalid: " + identifier);
        }
        return normalized;
    }
}
