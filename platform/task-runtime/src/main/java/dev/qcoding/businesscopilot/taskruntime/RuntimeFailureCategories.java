package dev.qcoding.businesscopilot.taskruntime;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

/**
 * CORE-06：业务错误到运行失败类别的统一映射。
 *
 * <p>五个业务模块把可诊断的失败归入运行时间线时使用同一映射，
 * 错误响应仍由 GlobalExceptionHandler 负责，不暴露 SQL、供应商原始异常或内部堆栈。</p>
 */
public final class RuntimeFailureCategories {

    private RuntimeFailureCategories() {
    }

    public static FailureCategory from(BusinessException ex) {
        return from(ex.errorCode());
    }

    public static FailureCategory from(ErrorCode errorCode) {
        return switch (errorCode) {
            case AI_MODEL_ERROR -> FailureCategory.PROVIDER;
            case AI_OUTPUT_PARSE_ERROR -> FailureCategory.MODEL;
            case SQL_GUARDRAIL_VIOLATION, SQL_CANDIDATE_NOT_EXECUTABLE -> FailureCategory.PERMISSION;
            case QUERY_EXECUTION_ERROR -> FailureCategory.PROVIDER;
            case STATE_CONFLICT -> FailureCategory.STATE_CONFLICT;
            default -> FailureCategory.UNKNOWN_OUTCOME;
        };
    }
}
