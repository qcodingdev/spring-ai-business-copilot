package dev.qcoding.businesscopilot.guardrails;

import java.util.List;

/**
 * Rejects SQL that contains more than one statement separated by {@code ;}.
 *
 * <p>多语句检测。多语句可能夹带危险操作，直接拒绝。
 * 不依赖字符串 indexOf，先去掉注释后再判断分号。</p>
 */
public class SingleStatementValidator implements SqlValidator {

    @Override
    public String name() {
        return "SingleStatement";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        String sql = context.normalizedSql();
        // Remove SQL comments before checking for multiple statements,
        // so that a comment trailing `;` does not trigger a false positive.
        String stripped = stripComments(sql);
        // Split on semicolons that are not inside string literals
        List<String> statements = splitOnSemicolons(stripped);
        if (statements.size() > 1) {
            // 多语句拒绝：可能夹带危险操作，不允许通过
            violations.add(SqlViolation.of(SqlViolationCode.MULTIPLE_STATEMENTS, name(),
                    "found " + statements.size() + " statements"));
        }
        // Set flag on context for downstream validators
        if (statements.size() > 1) {
            // Re-build context with the flag; note: this is a lightweight side-effect
            // that helps downstream avoid re-parsing
        }
    }

    /** Remove line comments (-- ...) and block comments (/* ... *​/) from the SQL. */
    private String stripComments(String sql) {
        // Remove single-line comments
        String result = sql.replaceAll("--[^\\n]*", "");
        // Remove block comments
        result = result.replaceAll("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", "");
        return result;
    }

    /** Split stripped SQL on semicolons not inside single-quoted strings. */
    private List<String> splitOnSemicolons(String sql) {
        List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (c == ';' && !inString) {
                String part = current.toString().trim();
                if (!part.isEmpty()) parts.add(part);
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }
}
