-- V9: associate every module audit record with the HTTP request and authenticated actor.
-- Existing rows remain nullable because they predate authentication.

ALTER TABLE query_audit_logs ADD COLUMN IF NOT EXISTS http_request_id VARCHAR(64);
ALTER TABLE query_audit_logs ADD COLUMN IF NOT EXISTS actor_id VARCHAR(100);
ALTER TABLE knowledge_qa_audit_logs ADD COLUMN IF NOT EXISTS http_request_id VARCHAR(64);
ALTER TABLE knowledge_qa_audit_logs ADD COLUMN IF NOT EXISTS actor_id VARCHAR(100);
ALTER TABLE support_audit_logs ADD COLUMN IF NOT EXISTS http_request_id VARCHAR(64);
ALTER TABLE support_audit_logs ADD COLUMN IF NOT EXISTS actor_id VARCHAR(100);
ALTER TABLE report_audit_logs ADD COLUMN IF NOT EXISTS http_request_id VARCHAR(64);
ALTER TABLE report_audit_logs ADD COLUMN IF NOT EXISTS actor_id VARCHAR(100);
ALTER TABLE resume_audit_logs ADD COLUMN IF NOT EXISTS http_request_id VARCHAR(64);
ALTER TABLE resume_audit_logs ADD COLUMN IF NOT EXISTS actor_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_query_audit_logs_http_request_created_at
    ON query_audit_logs(http_request_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_qa_audit_logs_http_request_created_at
    ON knowledge_qa_audit_logs(http_request_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_audit_logs_http_request_created_at
    ON support_audit_logs(http_request_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_report_audit_logs_http_request_created_at
    ON report_audit_logs(http_request_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resume_audit_logs_http_request_created_at
    ON resume_audit_logs(http_request_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_query_audit_logs_actor_created_at
    ON query_audit_logs(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_qa_audit_logs_actor_created_at
    ON knowledge_qa_audit_logs(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_audit_logs_actor_created_at
    ON support_audit_logs(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_report_audit_logs_actor_created_at
    ON report_audit_logs(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resume_audit_logs_actor_created_at
    ON resume_audit_logs(actor_id, created_at DESC);
