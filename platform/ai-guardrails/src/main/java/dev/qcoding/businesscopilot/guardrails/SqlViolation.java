package dev.qcoding.businesscopilot.guardrails;

/**
 * A single guardrail violation found while validating generated SQL.
 *
 * @param code       stable {@link SqlViolationCode#code()}
 * @param message    human-readable detail (may include the offending token/table/column)
 * @param rule       name of the validator that produced the violation
 */
public record SqlViolation(String code, String message, String rule) {

    /** Build a violation from a known code with the default message. */
    public static SqlViolation of(SqlViolationCode code, String rule) {
        return new SqlViolation(code.code(), code.defaultMessage(), rule);
    }

    /** Build a violation with a custom detail message. */
    public static SqlViolation of(SqlViolationCode code, String rule, String detail) {
        String message = detail == null || detail.isBlank()
                ? code.defaultMessage()
                : code.defaultMessage() + ": " + detail;
        return new SqlViolation(code.code(), message, rule);
    }
}
