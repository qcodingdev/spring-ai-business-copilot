package dev.qcoding.businesscopilot.commonweb.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void okResponseCarriesDataAndSuccessFlag() {
        ApiResponse<String> response = ApiResponse.ok("payload");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.errorCode()).isNull();
        assertThat(response.message()).isEqualTo("OK");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void okResponseWithCustomMessage() {
        ApiResponse<String> response = ApiResponse.ok("payload", "generated");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("generated");
    }

    @Test
    void failureResponseCarriesErrorCodeAndMessage() {
        ApiResponse<Void> response = ApiResponse.fail(ErrorCode.SQL_GUARDRAIL_VIOLATION);

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.errorCode()).isEqualTo(ErrorCode.SQL_GUARDRAIL_VIOLATION.code());
        assertThat(response.message()).isEqualTo(ErrorCode.SQL_GUARDRAIL_VIOLATION.defaultMessage());
    }

    @Test
    void failureResponseOverridesDefaultMessage() {
        ApiResponse<Void> response = ApiResponse.fail(ErrorCode.NOT_FOUND, "audit log not found");

        assertThat(response.success()).isFalse();
        assertThat(response.message()).isEqualTo("audit log not found");
    }
}
