package dev.qcoding.businesscopilot.commonsecurity;

/** 需要业务对象级授权的高风险操作。 */
public enum ObjectAction {
    CREATE,
    EXECUTE,
    CONFIRM,
    CANCEL,
    EXPORT,
    REVIEW
}
