package dev.qcoding.businesscopilot.evaluation;

import java.util.List;

/**
 * 可控故障注入（EVAL-02 隔离环境与可控故障）。
 *
 * <p>测试环境可初始化测试数据库、固定时钟和模型响应，并按案例模拟超时、限流、
 * 断连、重启和重复请求。故障注入只作用于隔离测试环境，不向真实客户、供应商或
 * 生产业务系统重放动作（X-10）。</p>
 */
public enum FaultScenario {
    /** 模型或外部调用超时。 */
    TIMEOUT,
    /** 供应商限流。 */
    RATE_LIMITED,
    /** 网络断连（结果未知）。 */
    DISCONNECT,
    /** 进程在动作后重启。 */
    RESTART_AFTER_ACTION,
    /** 同一确认或请求被重复提交。 */
    DUPLICATE_REQUEST
}
