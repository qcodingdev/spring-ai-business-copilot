package dev.qcoding.businesscopilot.datacopilot.generation;

import dev.qcoding.businesscopilot.guardrails.SqlValidationResult;

import java.util.List;

/**
 * Summary of guardrails validation applied to a generated SQL candidate.
 *
 * @param passed     whether the SQL passed all guardrails
 * @param violations list of violation descriptions (empty if passed)
 */
public record SqlCandidateValidationSummary(
        boolean passed,
        List<String> violations) {

    /** Build from a full {@link SqlValidationResult}. */
    public static SqlCandidateValidationSummary from(SqlValidationResult result) {
        List<String> violations = result.violations() == null
                ? List.of()
                : result.violations().stream().map(v -> v.code() + ": " + v.message()).toList();
        return new SqlCandidateValidationSummary(result.passed(), violations);
    }
}
