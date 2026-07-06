package dev.qcoding.businesscopilot.commonweb.api;

import java.util.List;

/**
 * Validation error details returned when Jakarta Validation constraints fail.
 *
 * <p>参数校验错误结构。每个字段错误单独列出，不暴露内部堆栈。</p>
 *
 * @param errorCode  fixed error code for validation failures
 * @param message    overall message
 * @param fieldErrors per-field violation list
 */
public record ValidationErrorResponse(
        String errorCode,
        String message,
        List<FieldError> fieldErrors) {

    /** Single field-level validation violation. */
    public record FieldError(String field, String message) {
    }

    /** Build a validation error response from a list of field violations. */
    public static ValidationErrorResponse of(List<FieldError> fieldErrors) {
        return new ValidationErrorResponse(
                ErrorCode.VALIDATION_ERROR.code(),
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                fieldErrors);
    }
}
