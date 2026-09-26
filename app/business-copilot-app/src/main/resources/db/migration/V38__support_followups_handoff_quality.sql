-- SUP-02：转人工原因（区分证据不足、过期证据、风险承诺、权限限制、工具失败）。
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS handoff_reason VARCHAR(40);

-- SUP-01：待人工审核的追问建议（确定性生成，不自动发送客户）。
ALTER TABLE support_reply_drafts ADD COLUMN IF NOT EXISTS followup_questions JSONB;

-- SUP-05：复核反馈转质量案例（脱敏内容，关联问题类型、修订原因与版本）。
CREATE TABLE IF NOT EXISTS support_quality_cases (
    id              BIGSERIAL PRIMARY KEY,
    ticket_ref      VARCHAR(64) NOT NULL,
    case_type       VARCHAR(40) NOT NULL,
    failure_summary VARCHAR(1000) NOT NULL,
    revision_reason VARCHAR(1000),
    draft_id        BIGINT,
    draft_version   VARCHAR(200),
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_support_quality_cases_type
    ON support_quality_cases(case_type, created_at DESC);
