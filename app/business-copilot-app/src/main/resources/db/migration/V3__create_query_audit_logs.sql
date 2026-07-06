-- V3: 创建审计表 query_audit_logs。
-- 字段必须匹配 ai-tool-audit 的 JdbcQueryAuditRepository（INSERT/SELECT 列名）。
-- 审计日志只记录元信息，不记录完整查询结果或敏感字段明文值。
-- query_audit_logs 不在 Data Copilot schema 白名单中，自然语言查询无法触达。

CREATE TABLE IF NOT EXISTS query_audit_logs (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          VARCHAR(64),
    user_question       TEXT,
    generated_sql       TEXT,
    final_sql           TEXT,
    validation_status   VARCHAR(50),
    validation_errors   TEXT,
    confirmed           BOOLEAN      NOT NULL DEFAULT FALSE,
    execution_status    VARCHAR(50),
    row_count           INTEGER,
    error_message       TEXT,
    model_name          VARCHAR(100),
    latency_ms          BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_query_audit_logs_created_at ON query_audit_logs(created_at DESC);
