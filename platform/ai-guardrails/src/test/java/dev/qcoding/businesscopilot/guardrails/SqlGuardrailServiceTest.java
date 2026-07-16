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
                new ColumnWhitelistValidator(properties.queryableColumns()),
                new FunctionAllowlistValidator(properties.allowedAggregateFunctions()),
                new SensitiveFieldValidator(policy),
                new LimitRequiredValidator(
                        properties.defaultMaxRows(),
                        properties.requireLimit(),
                        properties.allowedAggregateFunctions())
        );
        service = new SqlGuardrailService(validators);
    }

    // ---- Pass cases ----

    @Test
    @DisplayName("simple SELECT passes")
    void simpleSelectPasses() {
        SqlValidationResult result = service.validate(
                "SELECT id, name FROM public.customers LIMIT 10", properties);
        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("WITH SELECT passes")
    void withSelectPasses() {
        SqlValidationResult result = service.validate(
                "WITH recent_orders AS (SELECT id, customer_id, created_at FROM public.orders "
                        + "WHERE created_at > '2024-01-01') "
                        + "SELECT id, customer_id FROM recent_orders LIMIT 10", properties);
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("wildcard SELECT rejected by column allowlist")
    void wildcardSelectRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM public.customers LIMIT 10", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.COLUMN_NOT_WHITELISTED.code()));
    }

    @Test
    @DisplayName("column outside table allowlist rejected")
    void columnOutsideTableAllowlistRejected() {
        SqlValidationResult result = service.validate(
                "SELECT internal_note FROM public.customers LIMIT 10", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.COLUMN_NOT_WHITELISTED.code()));
    }

    @Test
    @DisplayName("MySQL backtick-qualified allowed columns pass")
    void mysqlBacktickQualifiedColumnsPass() {
        GuardrailsProperties mysqlProperties = new GuardrailsProperties(
                List.of("business_target.customers"),
                List.of("business_target.customers.id", "business_target.customers.email"),
                List.of("password", "token", "secret", "id_card"),
                List.of("email"), 100, true,
                List.of("count", "sum", "avg", "min", "max"));
        SensitiveFieldPolicy mysqlPolicy = new SensitiveFieldPolicy(mysqlProperties);
        SqlGuardrailService mysqlService = new SqlGuardrailService(List.of(
                new SingleStatementValidator(),
                new ReadOnlyStatementValidator(),
                new ForbiddenKeywordValidator(),
                new SchemaWhitelistValidator(mysqlProperties.queryableTables()),
                new ColumnWhitelistValidator(mysqlProperties.queryableColumns()),
                new FunctionAllowlistValidator(mysqlProperties.allowedAggregateFunctions()),
                new SensitiveFieldValidator(mysqlPolicy),
                new LimitRequiredValidator(100, true, mysqlProperties.allowedAggregateFunctions())));

        SqlValidationResult result = mysqlService.validate(
                "SELECT `id`, `email` FROM `business_target`.`customers` LIMIT 10",
                mysqlProperties);

        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("MySQL backtick-quoted blocked column is rejected")
    void mysqlBacktickBlockedColumnRejected() {
        SqlValidationResult result = service.validate(
                "SELECT `password` FROM `public`.`customers` LIMIT 10", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("aggregate query without LIMIT passes")
    void aggregateWithoutLimitPasses() {
        SqlValidationResult result = service.validate(
                "SELECT COUNT(*) FROM public.orders", properties);
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("SELECT with phone/email passes (masked later)")
    void selectWithMaskedColumnsPasses() {
        SqlValidationResult result = service.validate(
                "SELECT name, phone, email FROM public.customers LIMIT 10", properties);
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
                "SELECT * FROM public.customers /* drop table customers */ LIMIT 10", properties);
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
                "SELECT * FROM public.query_audit_logs LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.TABLE_NOT_WHITELISTED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (password) blocked")
    void passwordFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT password FROM public.customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (token) blocked")
    void tokenFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT token FROM public.customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (secret) blocked")
    void secretFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT secret FROM public.customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field (id_card) blocked")
    void idCardFieldBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT id_card FROM public.customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field inside expression blocked")
    void sensitiveFieldInsideExpressionBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT substring(password, 1, 2) AS password_prefix FROM public.customers LIMIT 10", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("high-sensitivity field inside CASE expression blocked")
    void sensitiveFieldInsideCaseExpressionBlocked() {
        SqlValidationResult result = service.validate(
                "SELECT CASE WHEN token IS NULL THEN 'missing' ELSE 'present' END AS token_status FROM public.customers LIMIT 10",
                properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.SENSITIVE_FIELD_BLOCKED.code()));
    }

    @Test
    @DisplayName("non-aggregate query without LIMIT rejected")
    void missingLimitRejected() {
        SqlValidationResult result = service.validate(
                "SELECT id, name FROM public.customers", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_REQUIRED.code()));
    }

    @Test
    @DisplayName("LIMIT exceeding max rejected")
    void limitExceedsMaxRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM public.customers LIMIT 500", properties);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_EXCEEDS_MAX.code()));
    }

    @Test
    @DisplayName("unqualified table rejected when whitelist is schema-qualified")
    void unqualifiedTableRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM customers LIMIT 10", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.TABLE_NOT_WHITELISTED.code()));
    }

    @Test
    @DisplayName("same-name table in another schema rejected")
    void sameNameCrossSchemaTableRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM private.customers LIMIT 10", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.TABLE_NOT_WHITELISTED.code()));
    }

    @Test
    @DisplayName("cross-schema table inside expression subquery rejected")
    void crossSchemaTableInsideSubqueryRejected() {
        SqlValidationResult result = service.validate("""
                SELECT id
                FROM public.customers
                WHERE id IN (
                    SELECT customer_id FROM private.orders LIMIT 10
                )
                LIMIT 10
                """, properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.TABLE_NOT_WHITELISTED.code()));
    }

    @Test
    @DisplayName("quoted lowercase qualified table passes")
    void quotedLowercaseQualifiedTablePasses() {
        SqlValidationResult result = service.validate(
                "SELECT id FROM \"public\".\"customers\" LIMIT 10", properties);

        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("ordinary function rejected")
    void ordinaryFunctionRejected() {
        SqlValidationResult result = service.validate(
                "SELECT LOWER(name) FROM public.customers LIMIT 10", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.FUNCTION_NOT_ALLOWED.code()));
    }

    @Test
    @DisplayName("dangerous sleep function rejected")
    void sleepFunctionRejected() {
        SqlValidationResult result = service.validate(
                "SELECT pg_sleep(1) FROM public.customers LIMIT 1", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.FUNCTION_NOT_ALLOWED.code()));
    }

    @Test
    @DisplayName("table function rejected")
    void tableFunctionRejected() {
        SqlValidationResult result = service.validate(
                "SELECT * FROM generate_series(1, 10) LIMIT 1", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.FUNCTION_NOT_ALLOWED.code()));
    }

    @Test
    @DisplayName("schema-qualified aggregate function rejected")
    void schemaQualifiedAggregateFunctionRejected() {
        SqlValidationResult result = service.validate(
                "SELECT pg_catalog.count(*) FROM public.orders", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.FUNCTION_NOT_ALLOWED.code()));
    }

    @Test
    @DisplayName("explicit aggregate allowlist passes")
    void aggregateAllowlistPasses() {
        for (String function : List.of("COUNT(*)", "SUM(total_amount)", "AVG(total_amount)",
                "MIN(total_amount)", "MAX(total_amount)")) {
            SqlValidationResult result = service.validate(
                    "SELECT " + function + " FROM public.orders", properties);
            assertThat(result.passed()).as(function).isTrue();
        }
    }

    @Test
    @DisplayName("grouped aggregate still requires bounded LIMIT")
    void groupedAggregateRequiresLimit() {
        SqlValidationResult result = service.validate(
                "SELECT status, COUNT(*) FROM public.orders GROUP BY status", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_REQUIRED.code()));
    }

    @Test
    @DisplayName("parameterized LIMIT rejected")
    void parameterizedLimitRejected() {
        SqlValidationResult result = service.validate(
                "SELECT id FROM public.customers LIMIT ?", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_NOT_BOUNDED_CONSTANT.code()));
    }

    @Test
    @DisplayName("computed LIMIT rejected")
    void computedLimitRejected() {
        SqlValidationResult result = service.validate(
                "SELECT id FROM public.customers LIMIT 1 + 1", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_NOT_BOUNDED_CONSTANT.code()));
    }

    @Test
    @DisplayName("negative LIMIT rejected")
    void negativeLimitRejected() {
        SqlValidationResult result = service.validate(
                "SELECT id FROM public.customers LIMIT -1", properties);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.code().equals(SqlViolationCode.LIMIT_NOT_BOUNDED_CONSTANT.code()));
    }

    @Test
    @DisplayName("zero LIMIT is a bounded constant")
    void zeroLimitPasses() {
        SqlValidationResult result = service.validate(
                "SELECT id FROM public.customers LIMIT 0", properties);

        assertThat(result.passed()).isTrue();
    }
}
