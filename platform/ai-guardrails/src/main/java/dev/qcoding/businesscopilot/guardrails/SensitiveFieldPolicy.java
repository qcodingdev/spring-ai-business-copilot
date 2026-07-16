package dev.qcoding.businesscopilot.guardrails;

import java.util.List;

/**
 * Policy mapping sensitive column names to blocking/masking behaviour.
 *
 * <p>敏感字段策略。确定哪些列直接阻断、哪些列查询后脱敏。
 * 配置中的 blockedColumns 和 maskedColumns 在此合并为统一策略。</p>
 */
public class SensitiveFieldPolicy {

    private final List<String> blockedColumns;
    private final List<MaskingRule> maskingRules;

    public SensitiveFieldPolicy(GuardrailsProperties properties) {
        this.blockedColumns = properties.blockedColumns();
        this.maskingRules = properties.maskedColumns().stream()
                .map(col -> new MaskingRule(col, MaskingStrategy.MASK))
                .toList();
    }

    /** Test whether a column name (case-insensitive) is blocked from direct query. */
    public boolean isBlocked(String columnName) {
        if (columnName == null) return false;
        String normalized = SqlIdentifierCanonicalizer.identifier(columnName.trim());
        return blockedColumns.stream()
                .anyMatch(bc -> bc.equalsIgnoreCase(normalized));
    }

    /** Find the masking rule for a column name, or {@code null} if none applies. */
    public MaskingRule findMaskingRule(String columnName) {
        if (columnName == null) return null;
        String normalized = SqlIdentifierCanonicalizer.identifier(columnName.trim());
        return maskingRules.stream()
                .filter(rule -> rule.matches(normalized))
                .findFirst()
                .orElse(null);
    }

    /** All blocked column names. */
    public List<String> blockedColumns() {
        return blockedColumns;
    }

    /** All masking rules. */
    public List<MaskingRule> maskingRules() {
        return maskingRules;
    }
}
