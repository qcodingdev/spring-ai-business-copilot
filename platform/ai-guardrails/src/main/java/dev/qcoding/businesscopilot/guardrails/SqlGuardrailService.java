package dev.qcoding.businesscopilot.guardrails;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade that runs all registered SQL validators and produces a single {@link SqlValidationResult}.
 *
 * <p>SQL 安全校验服务。按固定顺序执行校验链，任一违规即记录。
 * 校验失败绝不允许执行——这是 Data Copilot 的安全红线。</p>
 */
public class SqlGuardrailService {

    public static final String POLICY_VERSION = "sql-guardrails-v1.1";

    private final List<SqlValidator> validators;

    public SqlGuardrailService(List<SqlValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    /** Validate the given SQL and return the aggregate result. */
    public SqlValidationResult validate(String sql, GuardrailsProperties properties) {
        if (sql == null || sql.isBlank()) {
            return SqlValidationResult.fail(sql, List.of(
                    SqlViolation.of(SqlViolationCode.UNPARSEABLE, "PreCheck", "SQL is empty")));
        }
        // Pre-check for multiple statements at text level (before parser)
        boolean multiStatement = looksLikeMultipleStatements(sql);
        SqlValidationContext context = SqlValidationContext.forSql(sql, properties)
                .statementsSeparated(multiStatement)
                .build();

        List<SqlViolation> violations = new ArrayList<>();
        for (SqlValidator validator : validators) {
            validator.validate(context, violations);
        }

        if (violations.isEmpty()) {
            return SqlValidationResult.pass(context.normalizedSql());
        }
        return SqlValidationResult.fail(context.normalizedSql(), violations);
    }

    /** Quick text-level check: count semicolons outside string literals. */
    private boolean looksLikeMultipleStatements(String sql) {
        boolean inString = false;
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (c == ';' && !inString) {
                count++;
                if (count >= 2) return true;
            }
        }
        return false;
    }
}
