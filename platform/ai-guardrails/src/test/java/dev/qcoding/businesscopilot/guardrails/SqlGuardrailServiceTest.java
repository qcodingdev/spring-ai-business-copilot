package dev.qcoding.businesscopilot.guardrails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlGuardrailServiceTest {

    private SqlGuardrailService service;
    private GuardrailsProperties properties;

    @BeforeEach
    void setUp() {
        // Default configuration matching v1 security boundary
        properties = new GuardrailsProperties(null, null, null, 0, true);
        SensitiveFieldPolicy policy = new SensitiveFieldPolicy(properties);
        List<SqlValidator> validators = List.of(
                new SingleStatementValidator(),
                new ReadOnlyStatementValidator(),
                new ForbiddenKeywordValidator(),
                new SchemaWhitelistValidator(properties.queryableTables()),
                new SensitiveFieldValidator(policy),
                new LimitRequiredValidator(properties.defaultMaxRows(), properties.requireLimit())
        );
        service = new SqlGuardrailService(validators);
    }

    // ---- Pass cases ----

    @Test
    @DisplayName("simple SELECT passes")
    void simpleSelectPasses() {
        SqlValidationResult result = service.validate(
                "SELECT id, name FROM customers LIMIT 10", properties);
        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("WITH SELECT passes")
    void withSelectPasses() {
        SqlValidationResult result = service.validate(
                "WITH recent_orders AS (SELECT * FROM orders WHERE created_at > '2024-01-01') " +
                "SELECT * FROM recent_orders LIMIT 10", properties);
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("aggregate query without LIMIT passes")
    void aggregateWithoutLimitPasses() {
        SqlValidationResult result = service.validate(
                "SELECT COUNT(*) FROM orders", properties);
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("SELECT with phone/email passes (masked later)")
    void selectWithMaskedColumnsPasses() {
        SqlValidationResult result = service.validate(
                "SELECT name, phone, email FROM customers LIMIT 10", properties);
        assertThat(result.passed()).isTrue();
    }

    // ---- Reject cases ----

    @Test
    @DisplayName("INSERT rejected")
    void insertRejected() {
        SqlValidationResult result = service.validate(
                "INSERT INTO customers (name) VALUES ('test')", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("UPDATE rejected")
    void updateRejected() {
        SqlValidationResult result = service.validate(
                "UPDATE customers SET name = 'x' WHERE id = 1", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("DELETE rejected")
    void deleteRejected() {
        SqlValidationResult result = service.validate(
                "DELETE FROM customers WHERE id = 1", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("DROP rejected")
    void dropRejected() {
        SqlValidationResult result = service.validate(
                "DROP TABLE customers", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("ALTER rejected")
    void alterRejected() {
        SqlValidationResult result = service.validate(
                "ALTER TABLE customers ADD COLUMN test VARCHAR(10)", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("TRUNCATE rejected")
    void truncateRejected() {
        SqlValidationResult result = service.validate(
                "TRUNCATE TABLE customers", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("CREATE TABLE rejected")
    void createRejected() {
        SqlValidationResult result = service.validate(
                "CREATE TABLE hack (id INT)", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("GRANT rejected")
    void grantRejected() {
        SqlValidationResult result = service.validate(
                "GRANT ALL ON customers TO public", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("MERGE rejected")
    void mergeRejected() {
        SqlValidationResult result = service.validate(
                "MERGE INTO customers USING src ON (customers.id = src.id) WHEN MATCHED THEN UPDATE SET name = src.name", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("multiple statements rejected")
    void multipleStatementsRejected() {
        SqlValidationResult result = service.validate(
                "SELECT 1; SELECT 2", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.MULTIPLE_STATEMENTS.code()));
    }

    @Test
    @DisplayName("SQL comment smuggling forbidden keyword rejected")
    void commentSmugglingRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM customers /* drop table customers */ LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.FORBIDDEN_KEYWORD.code()));
    }

    @Test
    @DisplayName("parser failure rejected")
    void parserFailureRejected() {
        SqlValidationResult result = service.validate(
                "NOT VALID SQL AT ALL !!@@##", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.UNPARSEABLE.code()));
    }

    @Test
    @DisplayName("non-whitelisted table rejected")
    void nonWhitelistedTableRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM query_audit_logs LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.TABLE_NOT_WHITELISTED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (password) blocked")
    void passwordFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT password FROM customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (token) blocked")
    void tokenFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT token FROM customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (secret) blocked")
    void secretFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT secret FROM customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (id_card) blocked")
    void idCardFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT id_card FROM customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("non-aggregate query without LIMIT rejected")
    void missingLimitRejected() {
        SqlValidationResult result = service.validate(
                "SELECT id, name FROM customers", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_REQUIRED.code()));
    }

    @Test
    @DisplayName("LIMIT exceeding max rejected")
    void limitExceedsMaxRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM customers LIMIT 500", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_EXCEEDS_MAX.code()));
    }
}
