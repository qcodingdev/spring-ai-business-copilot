package dev.qcoding.businesscopilot.commonweb.api;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;

import java.time.Instant;

/**
 * Unified API envelope returned by every REST endpoint.
 *
 * <p>成功响应：{@code success=true}，携带 {@code data} 与 {@code message}。
 * 失败响应：{@code success=false}，携带 {@code errorCode} 与 {@code message}。</p>
 *
 * @param data       payload on success, {@code null} on failure
 * @param success    whether the request succeeded
 * @param errorCode  stable error code, present only on failure
 * @param message    human-readable message
 * @param requestId  HTTP request identifier for support and audit correlation
 * @param timestamp  response creation instant (ISO-8601 UTC)
 * @param <T>        payload type
 */
public record ApiResponse<T>(
        T data,
        boolean success,
        String errorCode,
        String message,
        String requestId,
        Instant timestamp) {

    /** Build a success response carrying {@code data} and a generic ok message. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, true, null, "OK", currentRequestId(), Instant.now());
    }

    /** Build a success response carrying {@code data} and a custom message. */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(data, true, null, message, currentRequestId(), Instant.now());
    }

    /** Build a failure response from a known {@link ErrorCode}. */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(null, false, errorCode.code(), errorCode.defaultMessage(), currentRequestId(), Instant.now());
    }

    /** Build a failure response from a known {@link ErrorCode} with a custom message. */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(null, false, errorCode.code(), message, currentRequestId(), Instant.now());
    }

    /** Build a failure response that also carries bounded, client-safe details. */
    public static <T> ApiResponse<T> fail(T data, ErrorCode errorCode, String message) {
        return new ApiResponse<>(data, false, errorCode.code(), message, currentRequestId(), Instant.now());
    }

    /** Build a failure response from a raw error code string and message. */
    public static <T> ApiResponse<T> fail(String errorCode, String message) {
        return new ApiResponse<>(null, false, errorCode, message, currentRequestId(), Instant.now());
    }

    private static String currentRequestId() {
        return BusinessRequestContextHolder.currentRequestId();
    }
}
