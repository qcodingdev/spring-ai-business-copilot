package dev.qcoding.businesscopilot.commonweb.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Thrown to surface a business-level error with a stable {@link ErrorCode}.
 *
 * <p>业务异常基类。Controller 层由 {@code GlobalExceptionHandler} 统一捕获，
 * 转换为对前端友好的错误结构，不暴露内部堆栈。</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<String> details;

    /** Build a business exception with the default message of the error code. */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.details = List.of();
    }

    /** Build a business exception with a custom message. */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = List.of();
    }

    /** Build a business exception with a custom message and underlying cause. */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = List.of();
    }

    /** Build a business exception with structured detail lines (e.g. guardrail violations). */
    public BusinessException(ErrorCode errorCode, String message, List<String> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : new ArrayList<>(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<String> details() {
        return details;
    }
}
