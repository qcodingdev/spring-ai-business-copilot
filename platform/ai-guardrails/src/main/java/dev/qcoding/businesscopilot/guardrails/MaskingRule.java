package dev.qcoding.businesscopilot.guardrails;

/**
 * A single masking rule describing how a column name is transformed.
 *
 * <p>脱敏规则。匹配列名（忽略大小写），并提供具体的脱敏实现。</p>
 *
 * @param columnPattern column name to match (case-insensitive)
 * @param strategy      {@link MaskingStrategy#MASK} for masking rules
 */
public record MaskingRule(String columnPattern, MaskingStrategy strategy) {

    /** Whether a given column name matches this rule. */
    public boolean matches(String columnName) {
        return columnName != null && columnPattern != null
                && columnPattern.equalsIgnoreCase(columnName.trim());
    }
}
