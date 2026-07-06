package dev.qcoding.businesscopilot.guardrails;

/**
 * A single guardrail validator in the SQL safety chain.
 *
 * <p>SQL 校验器接口。每个校验器只负责一类规则，校验链顺序执行，
 * 任一校验器返回违规即记录到结果中。</p>
 */
public interface SqlValidator {

    /** Short rule name, used in {@link SqlViolation#rule()} for traceability. */
    String name();

    /**
     * Validate the SQL and append any violations to {@code violations}.
     *
     * @param context    parsing/validation context shared across the chain
     * @param violations mutable accumulator for violations
     */
    void validate(SqlValidationContext context, java.util.List<SqlViolation> violations);
}
