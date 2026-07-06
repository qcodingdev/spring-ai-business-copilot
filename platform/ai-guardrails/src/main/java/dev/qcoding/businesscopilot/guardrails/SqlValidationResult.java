package dev.qcoding.businesscopilot.guardrails;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of running generated SQL through the guardrail validator chain.
 *
 * @param sql        the (possibly normalised) SQL that was validated
 * @param passed     {@code true} when there are no violations
 * @param violations list of violations; empty when passed
 */
public record SqlValidationResult(String sql, boolean passed, List<SqlViolation> violations) {

    /** Build a passing result. */
    public static SqlValidationResult pass(String sql) {
        return new SqlValidationResult(sql, true, List.of());
    }

    /** Build a failing result with the given violations. */
    public static SqlValidationResult fail(String sql, List<SqlViolation> violations) {
        return new SqlValidationResult(sql, false, new ArrayList<>(violations));
    }

    /** Convenience: are there any violations? */
    public boolean hasViolations() {
        return violations != null && !violations.isEmpty();
    }
}
