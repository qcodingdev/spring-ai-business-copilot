package dev.qcoding.businesscopilot.guardrails;

/**
 * Stable codes describing why a generated SQL violated a guardrail rule.
 *
 * <p>SQL 安全校验违规码。每个违规原因都有稳定 code，便于前端展示和测试断言。</p>
 */
public enum SqlViolationCode {

    /** More than one statement detected. */
    MULTIPLE_STATEMENTS("SQL_001", "不允许执行多条 SQL 语句"),

    /** JSQLParser could not parse the SQL. */
    UNPARSEABLE("SQL_002", "SQL 无法解析"),

    /** Statement is not a read-only SELECT or WITH...SELECT. */
    NOT_READ_ONLY("SQL_003", "只允许单条只读 SELECT 或 WITH ... SELECT"),

    /** Statement uses a forbidden keyword/statement type. */
    FORBIDDEN_KEYWORD("SQL_004", "包含禁止的 SQL 关键字或语句类型"),

    /** Statement references a table outside the whitelist. */
    TABLE_NOT_WHITELISTED("SQL_005", "数据表不在查询白名单中"),

    /** Statement directly queries a high-sensitivity blocked column. */
    SENSITIVE_FIELD_BLOCKED("SQL_006", "禁止直接查询高敏感字段"),

    /** Non-aggregate query lacks a LIMIT clause. */
    LIMIT_REQUIRED("SQL_007", "Non-aggregate query must include a LIMIT clause"),

    /** LIMIT exceeds the configured maximum. */
    LIMIT_EXCEEDS_MAX("SQL_008", "LIMIT 超过允许的最大值"),

    /** LIMIT is absent, parameterized, computed, negative, or otherwise not a bounded literal. */
    LIMIT_NOT_BOUNDED_CONSTANT("SQL_009", "LIMIT 必须是有上限的非负整数常量"),

    /** A database function is not present in the explicit aggregate allowlist. */
    FUNCTION_NOT_ALLOWED("SQL_010", "数据库函数不在允许列表中"),

    /** Statement references a wildcard or column outside the explicit allowlist. */
    COLUMN_NOT_WHITELISTED("SQL_011", "字段不在查询白名单中");

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
