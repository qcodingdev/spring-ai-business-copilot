package dev.qcoding.businesscopilot.audit;

/**
 * Lifecycle states of a Data Copilot query request.
 *
 * <p>审计状态：覆盖从问题输入到执行结束的完整生命周期。</p>
 */
public enum AuditStatus {
    /** Model call failed or produced an unusable result. */
    MODEL_FAILED,
    /** SQL generated but failed guardrail validation. */
    VALIDATION_FAILED,
    /** SQL validated but the user did not confirm execution. */
    NOT_CONFIRMED,
    /** Candidate confirmed and executed successfully. */
    EXECUTED,
    /** Execution failed at the database layer. */
    EXECUTION_FAILED,
    /** AI result explanation step failed (non-fatal; results still returned). */
    EXPLANATION_FAILED
}
