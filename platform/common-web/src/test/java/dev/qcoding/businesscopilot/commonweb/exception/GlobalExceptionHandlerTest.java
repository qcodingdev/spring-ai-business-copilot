package dev.qcoding.businesscopilot.commonweb.exception;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.api.ValidationErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    void businessExceptionMapsToHttpStatusFromBody() {
        BusinessException ex = new BusinessException(ErrorCode.NOT_FOUND, "audit log not found");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.NOT_FOUND.defaultMessage());
        assertThat(response.getBody().message()).doesNotContain("audit log");
    }

    @Test
    void businessErrorCanExposePurposeBuiltClientMessage() {
        BusinessException ex = new BusinessException(ErrorCode.BUSINESS_ERROR, "Report title is required");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Report title is required");
    }

    @Test
    void validationExceptionCollectsFieldErrors() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "question", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new MethodParameter(GlobalExceptionHandlerTest.class.getDeclaredMethod("validationExceptionCollectsFieldErrors"), -1),
                bindingResult);

        ResponseEntity<ApiResponse<ValidationErrorResponse>> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        ValidationErrorResponse validation = response.getBody().data();
        assertThat(validation.fieldErrors()).hasSize(1);
        ValidationErrorResponse.FieldError fieldError = validation.fieldErrors().get(0);
        assertThat(fieldError.field()).isEqualTo("question");
        assertThat(fieldError.message()).isEqualTo("must not be blank");
        assertThat(validation.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.code());
    }

    @Test
    void unexpectedExceptionReturnsGeneric500WithoutInternals() {
        Exception ex = new RuntimeException("NPE at internal service XYZ");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        // 错误信息不包含内部堆栈或异常细节
        assertThat(response.getBody().message()).doesNotContain("NPE", "XYZ");
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.code());
    }
}
