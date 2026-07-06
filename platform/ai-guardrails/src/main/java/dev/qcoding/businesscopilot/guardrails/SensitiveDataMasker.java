package dev.qcoding.businesscopilot.guardrails;

/**
 * Masks sensitive column values in query results before they reach the client or AI model.
 *
 * <p>查询结果脱敏器。根据 {@link SensitiveFieldPolicy} 对 phone、email 等字段做部分遮蔽，
 * 对被阻断的高敏字段如果意外出现在结果中也做全遮蔽。</p>
 */
public class SensitiveDataMasker {

    private final SensitiveFieldPolicy policy;

    public SensitiveDataMasker(SensitiveFieldPolicy policy) {
        this.policy = policy;
    }

    /**
     * Mask a single cell value based on its column name and the active policy.
     *
     * @param columnName the column name (case-insensitive matching)
     * @param value      the raw value to potentially mask
     * @return the masked value, or the original value if no rule applies
     */
    public String mask(String columnName, String value) {
        if (value == null || value.isBlank()) return value;
        if (policy.isBlocked(columnName)) {
            // 高敏字段出现在结果中时全遮蔽——阻断策略本应在校验阶段拒绝查询，
            // 但如果意外流入结果也做兜底遮蔽
            return "******";
        }
        MaskingRule rule = policy.findMaskingRule(columnName);
        if (rule == null) return value;
        return applyMasking(columnName.toLowerCase().trim(), value);
    }

    /** Apply column-specific masking logic. */
    private String applyMasking(String column, String value) {
        // phone: 保留前三后四，中间替换为 ****
        if ("phone".equalsIgnoreCase(column)) {
            return maskPhone(value);
        }
        // email: 保留首字符和域名，中间替换为 ***
        if ("email".equalsIgnoreCase(column)) {
            return maskEmail(value);
        }
        // 通用遮蔽：保留前两字符后加 ***
        if (value.length() <= 2) return "***";
        return value.substring(0, 2) + "***";
    }

    /** Mask phone: 138****0001 pattern. */
    private String maskPhone(String phone) {
        String digits = phone.replaceAll("[^\\d]", "");
        if (digits.length() <= 7) return "****";
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    /** Mask email: u***@example.com pattern. */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.charAt(0) + "***" + domain;
    }
}
