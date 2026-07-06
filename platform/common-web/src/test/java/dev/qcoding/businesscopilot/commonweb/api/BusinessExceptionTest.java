package dev.qcoding.businesscopilot.commonweb.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void mapsErrorCodeAndDefaultMessage() {
        BusinessException ex = new BusinessException(ErrorCode.NOT_FOUND);

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.defaultMessage());
        assertThat(ex.details()).isEmpty();
    }

    @Test
    void carriesCustomMessageAndCause() {
        Exception cause = new RuntimeException("db down");
        BusinessException ex = new BusinessException(ErrorCode.QUERY_EXECUTION_ERROR, "query failed", cause);

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.QUERY_EXECUTION_ERROR);
        assertThat(ex.getMessage()).isEqualTo("query failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void carriesStructuredDetails() {
        List<String> violations = List.of("forbidden: DELETE", "missing LIMIT");
        BusinessException ex = new BusinessException(
                ErrorCode.SQL_GUARDRAIL_VIOLATION, "guardrail violations", violations);

        assertThat(ex.details()).containsExactly("forbidden: DELETE", "missing LIMIT");
    }
}
