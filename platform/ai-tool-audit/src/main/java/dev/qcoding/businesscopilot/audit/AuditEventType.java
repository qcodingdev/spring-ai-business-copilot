package dev.qcoding.businesscopilot.audit;

/**
 * Type of audit event.
 *
 * <p>审计事件类型：成功、失败、取消（用户未确认）。</p>
 */
public enum AuditEventType {
    /** Query completed end to end. */
    QUERY_SUCCESS,
    /** Query failed at any stage. */
    QUERY_FAILURE,
    /** User did not confirm execution (or cancelled). */
    QUERY_NOT_CONFIRMED
}
