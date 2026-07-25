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
        log.warn("业务请求失败：uri={}，code={}，message={}", request.getRequestURI(),
                ex.errorCode().code(), ex.getMessage());

        HttpStatus status = mapToHttpStatus(ex.errorCode());
        String clientMessage = exposesBusinessMessage(ex.errorCode())
                ? ex.getMessage()
                : ex.errorCode().defaultMessage();
        ApiResponse<Void> body = ApiResponse.fail(ex.errorCode(), clientMessage);
        return ResponseEntity.status(status).body(body);
    }

    private boolean exposesBusinessMessage(ErrorCode errorCode) {
        return switch (errorCode) {
            case BUSINESS_ERROR, VALIDATION_ERROR, DOCUMENT_EMPTY, DOCUMENT_TOO_LARGE,
                    DOCUMENT_FORMAT_UNSUPPORTED, DOCUMENT_DUPLICATE,
                    PUBLIC_DEMO_INPUT_REJECTED, DEMO_SCENARIO_NOT_AVAILABLE,
                    PUBLIC_DEMO_LIMIT_REACHED -> true;
            case NOT_FOUND, STATE_CONFLICT, AI_MODEL_ERROR, AI_OUTPUT_PARSE_ERROR,
                    SQL_GUARDRAIL_VIOLATION, SQL_CANDIDATE_NOT_EXECUTABLE,
                    QUERY_EXECUTION_ERROR, EMBEDDING_DIMENSION_MISMATCH, INTERNAL_ERROR -> false;
        };
    }

    /**
     * Handle Jakarta Validation failures — collect field-level messages.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ValidationErrorResponse>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("请求参数校验失败：uri={}", request.getRequestURI());

        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        ValidationErrorResponse validation = ValidationErrorResponse.of(fieldErrors);
        ApiResponse<ValidationErrorResponse> body = ApiResponse.ok(validation, "请求参数校验失败");
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Catch-all handler — log internally, return a generic 500 without any internals.
     *
     * <p>兜底处理：只返回通用错误信息，不泄露内部细节。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("请求发生未预期异常：uri={}", request.getRequestURI(), ex);
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** 将 {@link ErrorCode} 映射为 HTTP 状态码。 */
    private HttpStatus mapToHttpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STATE_CONFLICT -> HttpStatus.CONFLICT;
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
            case PUBLIC_DEMO_INPUT_REJECTED -> HttpStatus.BAD_REQUEST;
            case DEMO_SCENARIO_NOT_AVAILABLE -> HttpStatus.NOT_FOUND;
            case PUBLIC_DEMO_LIMIT_REACHED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
