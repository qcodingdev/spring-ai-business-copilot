package dev.qcoding.businesscopilot.taskruntime;

/**
 * 任务级失败分类（CORE-06 / RUN-06）。
 *
 * <p>用于运行时间线上可解释的失败类别：区分模型、解析、权限、证据、预算、供应商、
 * 状态冲突、工具失败、人工等待和未知结果。错误响应不暴露 SQL、供应商原始异常或内部堆栈，
 * 类别本身是稳定、可展示的。</p>
 */
public enum FailureCategory {
    /** 模型调用失败或返回不可用结果。 */
    MODEL,
    /** 模型输出无法解析为预期结构。 */
    PARSE,
    /** 对象级或角色权限不满足。 */
    PERMISSION,
    /** 引用证据缺失、过期或不足。 */
    EVIDENCE,
    /** 预算（次数/Token/时长）耗尽。 */
    BUDGET,
    /** 供应商侧失败（限流、断连、超时）。 */
    PROVIDER,
    /** 业务对象状态冲突（重复确认、已被消费等）。 */
    STATE_CONFLICT,
    /** 工具或外部系统调用失败。 */
    TOOL,
    /** 等待人工动作（确认、复核、处置）。 */
    HUMAN_WAIT,
    /** 已发生副作用但结果未知，需先核对。 */
    UNKNOWN_OUTCOME
}
