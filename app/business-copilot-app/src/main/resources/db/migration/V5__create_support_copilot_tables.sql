-- V5: 创建 Support Copilot 相关表。
-- 创建工单表、回复草稿表和支持审计日志表。
-- 客户消息和草稿内容入库前必须脱敏。审计表不记录未脱敏原文。

-- ── 工单表 ─────────────────────────────────────────────────
-- 记录脱敏后的客户消息、渠道、分类、情绪、紧急程度和状态。
CREATE TABLE IF NOT EXISTS support_tickets (
    id              BIGSERIAL PRIMARY KEY,
    external_id     VARCHAR(200),
    customer_message TEXT         NOT NULL,
    channel         VARCHAR(50)   NOT NULL DEFAULT 'sample',
    category        VARCHAR(50),
    sentiment       VARCHAR(50),
    urgency         VARCHAR(50),
    status          VARCHAR(50)   NOT NULL DEFAULT 'DRAFTED',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_support_tickets_status ON support_tickets(status);
CREATE INDEX IF NOT EXISTS idx_support_tickets_created_at ON support_tickets(created_at DESC);

-- ── 回复草稿表 ─────────────────────────────────────────────
-- 记录脱敏后的回复草稿、引用片段、风险等级和确认 token。
-- confirmation_token 由服务端生成，expires_at 控制确认有效期。
CREATE TABLE IF NOT EXISTS support_reply_drafts (
    id                  BIGSERIAL PRIMARY KEY,
    ticket_id           BIGINT        NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    draft_text          TEXT          NOT NULL,
    cited_chunk_ids     TEXT,
    risk_level          VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',
    risk_reasons        TEXT,
    confirmation_token  VARCHAR(200),
    expires_at          TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_support_reply_drafts_ticket_id ON support_reply_drafts(ticket_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_support_reply_drafts_confirmation_token ON support_reply_drafts(confirmation_token);

-- ── 支持审计日志表 ─────────────────────────────────────────
-- 记录工单分析、草稿生成、人工确认/取消和异常等全生命周期审计。
-- 不记录未脱敏客户原文，不记录未脱敏草稿内容。
CREATE TABLE IF NOT EXISTS support_audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    request_id      VARCHAR(64),
    ticket_id       BIGINT,
    event_type      VARCHAR(50),
    category        VARCHAR(50),
    urgency         VARCHAR(50),
    risk_level      VARCHAR(20),
    cited_chunk_ids TEXT,
    model_name      VARCHAR(100),
    latency_ms      BIGINT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_support_audit_logs_created_at ON support_audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_audit_logs_event_type ON support_audit_logs(event_type);
