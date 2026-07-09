package dev.qcoding.businesscopilot.commonweb.exception;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.api.ValidationErrorResponse;
import dev.qcoding.businesscopilot.commonweb.api.ValidationErrorResponse.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Centralised exception handler that converts every throwable to a stable {@link ApiResponse}
 * without leaking stack traces or internal details to the client.
 *
 * <p>全局异常处理器。确保前端只收到可理解的错误结构，绝不暴露堆栈。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle {@link BusinessException} — translate to the appropriate HTTP status
     * derived from the embedded {@link ErrorCode}.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("Business error on {}: code={}, message={}", request.getRequestURI(),
                ex.errorCode().code(), ex.getMessage());

        HttpStatus status = mapToHttpStatus(ex.errorCode());
        ApiResponse<Void> body = ApiResponse.fail(ex.errorCode(), ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Handle Jakarta Validation failures — collect field-level messages.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ValidationErrorResponse>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("Validation error on {}", request.getRequestURI());

        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        ValidationErrorResponse validation = ValidationErrorResponse.of(fieldErrors);
        ApiResponse<ValidationErrorResponse> body = ApiResponse.ok(validation, "Validation failed");
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Catch-all handler — log internally, return a generic 500 without any internals.
     *
     * <p>兜底处理：只返回通用错误信息，不泄露内部细节。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** Map {@link ErrorCode} to an HTTP status code. */
    private HttpStatus mapToHttpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BUSINESS_ERROR -> HttpStatus.BAD_REQUEST;
            case SQL_GUARDRAIL_VIOLATION -> HttpStatus.BAD_REQUEST;
            case SQL_CANDIDATE_NOT_EXECUTABLE -> HttpStatus.BAD_REQUEST;
            case AI_MODEL_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_OUTPUT_PARSE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
            case QUERY_EXECUTION_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case DOCUMENT_EMPTY -> HttpStatus.BAD_REQUEST;
            case DOCUMENT_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case DOCUMENT_FORMAT_UNSUPPORTED -> HttpStatus.BAD_REQUEST;
            case DOCUMENT_DUPLICATE -> HttpStatus.CONFLICT;
            case EMBEDDING_DIMENSION_MISMATCH -> HttpStatus.INTERNAL_SERVER_ERROR;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
