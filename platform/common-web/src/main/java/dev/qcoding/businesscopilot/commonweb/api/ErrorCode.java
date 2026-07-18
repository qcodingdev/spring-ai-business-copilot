package dev.qcoding.businesscopilot.commonweb.api;

/** 各业务模块共享的稳定错误码；只覆盖当前真实使用的分类。 */
public enum ErrorCode {

    /** 未归入更具体分类的通用业务错误。 */
    BUSINESS_ERROR("BIZ_0001", "业务处理失败"),

    /** 输入未通过 Jakarta Validation 约束。 */
    VALIDATION_ERROR("BIZ_0002", "请求参数校验失败"),

    /** 请求的资源不存在。 */
    NOT_FOUND("BIZ_0003", "请求的资源不存在"),

    /** 资源存在，但当前状态不允许请求的状态转换。 */
    STATE_CONFLICT("BIZ_0004", "资源状态冲突"),

    /** 上游 AI 模型调用失败或返回不可用结果。 */
    AI_MODEL_ERROR("BIZ_0100", "AI 模型调用失败"),

    /** 模型输出无法解析成预期结构。 */
    AI_OUTPUT_PARSE_ERROR("BIZ_0101", "AI 输出解析失败"),

    /** 生成的 SQL 违反安全规则。 */
    SQL_GUARDRAIL_VIOLATION("BIZ_0200", "SQL 安全校验未通过"),

    /** SQL 候选已过期、撤销或不可执行。 */
    SQL_CANDIDATE_NOT_EXECUTABLE("BIZ_0201", "SQL 候选当前不可执行"),

    /** 只读查询执行失败。 */
    QUERY_EXECUTION_ERROR("BIZ_0300", "查询执行失败"),

    /** 上传文档为空。 */
    DOCUMENT_EMPTY("BIZ_0400", "上传文档为空"),

    /** 上传文档超过配置限制。 */
    DOCUMENT_TOO_LARGE("BIZ_0401", "上传文档超过大小限制"),

    /** 上传文档格式不受支持。 */
    DOCUMENT_FORMAT_UNSUPPORTED("BIZ_0402", "不支持该文档格式"),

    /** 上传内容与已有文档重复。 */
    DOCUMENT_DUPLICATE("BIZ_0403", "相同内容的文档已存在"),

    /** 模型返回的向量维度与应用配置不一致。 */
    EMBEDDING_DIMENSION_MISMATCH("BIZ_0404", "向量维度不匹配"),

    /** 不得向客户端泄露内部细节的服务器错误。 */
    INTERNAL_ERROR("SYS_5000", "服务器内部错误");

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
