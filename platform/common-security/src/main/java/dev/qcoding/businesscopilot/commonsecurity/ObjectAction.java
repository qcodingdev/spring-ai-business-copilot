package dev.qcoding.businesscopilot.commonsecurity;

/** High-risk actions that require object-level authorization. */
public enum ObjectAction {
    CREATE,
    EXECUTE,
    CONFIRM,
    CANCEL,
    EXPORT,
    REVIEW
}
