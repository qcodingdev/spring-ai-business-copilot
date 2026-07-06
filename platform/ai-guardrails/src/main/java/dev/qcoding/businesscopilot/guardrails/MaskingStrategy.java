package dev.qcoding.businesscopilot.guardrails;

/**
 * Strategy applied to a sensitive column.
 *
 * <p>敏感字段处理策略：</p>
 * <ul>
 *   <li>{@link #BLOCK} — high sensitivity, direct query is rejected at validation time.</li>
 *   <li>{@link #MASK} — queryable, value masked before returning to the client.</li>
 * </ul>
 */
public enum MaskingStrategy {
    /** Direct query of this column is rejected by the guardrail. */
    BLOCK,
    /** Column may be queried; values are masked before returning. */
    MASK
}
