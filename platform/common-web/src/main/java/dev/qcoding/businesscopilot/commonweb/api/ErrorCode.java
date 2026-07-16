package dev.qcoding.businesscopilot.commonweb.api;

/**
 * Stable error codes shared by every business module.
 *
 * <p>统一错误码枚举。Data Copilot 第一版只覆盖必要错误码，不预先构造大而全的体系。</p>
 */
public enum ErrorCode {

    /** Generic business error without a more specific classification. */
    BUSINESS_ERROR("BIZ_0001", "Business error"),

    /** Input failed Jakarta Validation constraints. */
    VALIDATION_ERROR("BIZ_0002", "Request validation failed"),

    /** Requested resource was not found. */
    NOT_FOUND("BIZ_0003", "Resource not found"),

    /** A visible object exists but its state no longer allows the requested transition. */
    STATE_CONFLICT("BIZ_0004", "Resource state conflict"),

    /** Upstream AI model invocation failed or returned an unusable response. */
    AI_MODEL_ERROR("BIZ_0100", "AI model invocation failed"),

    /** Model output could not be parsed as the expected structured result. */
    AI_OUTPUT_PARSE_ERROR("BIZ_0101", "AI output parse failed"),

    /** Generated SQL violated a guardrail rule. */
    SQL_GUARDRAIL_VIOLATION("BIZ_0200", "SQL guardrail violation"),

    /** SQL candidate was expired, revoked, or otherwise not executable. */
    SQL_CANDIDATE_NOT_EXECUTABLE("BIZ_0201", "SQL candidate is not executable"),

    /** Read-only query execution failed. */
    QUERY_EXECUTION_ERROR("BIZ_0300", "Query execution failed"),

    /** Uploaded document is empty. */
    DOCUMENT_EMPTY("BIZ_0400", "Uploaded document is empty"),

    /** Uploaded document exceeds the configured size limit. */
    DOCUMENT_TOO_LARGE("BIZ_0401", "Uploaded document exceeds size limit"),

    /** Uploaded document format is not supported. */
    DOCUMENT_FORMAT_UNSUPPORTED("BIZ_0402", "Unsupported document format"),

    /** Uploaded document content duplicates an existing document. */
    DOCUMENT_DUPLICATE("BIZ_0403", "Document with same content already exists"),

    /** Embedding dimension returned by model does not match the configured database column dimension. */
    EMBEDDING_DIMENSION_MISMATCH("BIZ_0404", "Embedding dimension mismatch"),

    /** Internal server error that must not leak internals to the client. */
    INTERNAL_ERROR("SYS_5000", "Internal server error");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
