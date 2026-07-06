package dev.qcoding.businesscopilot.commonweb.api;

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
 * @param timestamp  response creation instant (ISO-8601 UTC)
 * @param <T>        payload type
 */
public record ApiResponse<T>(
        T data,
        boolean success,
        String errorCode,
        String message,
        Instant timestamp) {

    /** Build a success response carrying {@code data} and a generic ok message. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, true, null, "OK", Instant.now());
    }

    /** Build a success response carrying {@code data} and a custom message. */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(data, true, null, message, Instant.now());
    }

    /** Build a failure response from a known {@link ErrorCode}. */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(null, false, errorCode.code(), errorCode.defaultMessage(), Instant.now());
    }

    /** Build a failure response from a known {@link ErrorCode} with a custom message. */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(null, false, errorCode.code(), message, Instant.now());
    }

    /** Build a failure response from a raw error code string and message. */
    public static <T> ApiResponse<T> fail(String errorCode, String message) {
        return new ApiResponse<>(null, false, errorCode, message, Instant.now());
    }
}
