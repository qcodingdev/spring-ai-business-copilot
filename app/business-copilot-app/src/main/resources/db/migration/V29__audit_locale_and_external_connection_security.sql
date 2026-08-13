-- 2.3.0：审计只记录固定低基数 locale，不记录完整 Accept-Language 或用户正文。
ALTER TABLE query_audit_logs
    ADD COLUMN IF NOT EXISTS locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN'
        CHECK (locale IN ('zh-CN', 'en-US'));
ALTER TABLE knowledge_qa_audit_logs
    ADD COLUMN IF NOT EXISTS locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN'
        CHECK (locale IN ('zh-CN', 'en-US'));
ALTER TABLE support_audit_logs
    ADD COLUMN IF NOT EXISTS locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN'
        CHECK (locale IN ('zh-CN', 'en-US'));
ALTER TABLE report_audit_logs
    ADD COLUMN IF NOT EXISTS locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN'
        CHECK (locale IN ('zh-CN', 'en-US'));
ALTER TABLE resume_audit_logs
    ADD COLUMN IF NOT EXISTS locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN'
        CHECK (locale IN ('zh-CN', 'en-US'));

CREATE INDEX IF NOT EXISTS idx_query_audit_locale_created_at
    ON query_audit_logs(locale, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_audit_locale_created_at
    ON knowledge_qa_audit_logs(locale, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_audit_locale_created_at
    ON support_audit_logs(locale, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_report_audit_locale_created_at
    ON report_audit_logs(locale, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resume_audit_locale_created_at
    ON resume_audit_logs(locale, created_at DESC);
