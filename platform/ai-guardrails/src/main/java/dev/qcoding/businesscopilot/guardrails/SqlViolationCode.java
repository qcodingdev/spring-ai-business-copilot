package dev.qcoding.businesscopilot.guardrails;

/**
 * Stable codes describing why a generated SQL violated a guardrail rule.
 *
 * <p>SQL 安全校验违规码。每个违规原因都有稳定 code，便于前端展示和测试断言。</p>
 */
public enum SqlViolationCode {

    /** More than one statement detected. */
    MULTIPLE_STATEMENTS("SQL_001", "Multiple statements are not allowed"),

    /** JSQLParser could not parse the SQL. */
    UNPARSEABLE("SQL_002", "SQL could not be parsed"),

    /** Statement is not a read-only SELECT or WITH...SELECT. */
    NOT_READ_ONLY("SQL_003", "Only single read-only SELECT or WITH ... SELECT is allowed"),

    /** Statement uses a forbidden keyword/statement type. */
    FORBIDDEN_KEYWORD("SQL_004", "Forbidden SQL keyword or statement type"),

    /** Statement references a table outside the whitelist. */
    TABLE_NOT_WHITELISTED("SQL_005", "Table is not in the query whitelist"),

    /** Statement directly queries a high-sensitivity blocked column. */
    SENSITIVE_FIELD_BLOCKED("SQL_006", "Direct query of high-sensitivity column is blocked"),

    /** Non-aggregate query lacks a LIMIT clause. */
    LIMIT_REQUIRED("SQL_007", "Non-aggregate query must include a LIMIT clause"),

    /** LIMIT exceeds the configured maximum. */
    LIMIT_EXCEEDS_MAX("SQL_008", "LIMIT exceeds the allowed maximum"),

    /** LIMIT is absent, parameterized, computed, negative, or otherwise not a bounded literal. */
    LIMIT_NOT_BOUNDED_CONSTANT("SQL_009", "LIMIT must be a bounded non-negative integer literal"),

    /** A database function is not present in the explicit aggregate allowlist. */
    FUNCTION_NOT_ALLOWED("SQL_010", "Database function is not allowed");

    private final String code;
    private final String defaultMessage;

    SqlViolationCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
