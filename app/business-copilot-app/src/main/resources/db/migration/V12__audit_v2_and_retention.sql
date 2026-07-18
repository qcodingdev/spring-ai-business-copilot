-- V12: provider/prompt/policy metadata plus bounded retention for module-owned audit tables.
-- Full prompts, model output bodies, query results, raw resumes, and support text remain excluded.

ALTER TABLE data_sql_candidates
    ADD COLUMN IF NOT EXISTS provider_name VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS input_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS output_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(80),
    ADD COLUMN IF NOT EXISTS model_latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64);

ALTER TABLE query_audit_logs
    ADD COLUMN IF NOT EXISTS provider_name VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS prompt_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS violation_codes TEXT,
    ADD COLUMN IF NOT EXISTS input_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS output_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(80),
    ADD COLUMN IF NOT EXISTS anonymize_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delete_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS anonymized_at TIMESTAMPTZ;

ALTER TABLE knowledge_qa_audit_logs
    ADD COLUMN IF NOT EXISTS creator_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS provider_name VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS prompt_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS violation_codes TEXT,
    ADD COLUMN IF NOT EXISTS input_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS output_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(80),
    ADD COLUMN IF NOT EXISTS anonymize_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delete_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS anonymized_at TIMESTAMPTZ;

ALTER TABLE support_audit_logs
    ADD COLUMN IF NOT EXISTS provider_name VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS prompt_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS violation_codes TEXT,
    ADD COLUMN IF NOT EXISTS input_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS output_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(80),
    ADD COLUMN IF NOT EXISTS anonymize_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delete_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS anonymized_at TIMESTAMPTZ;

ALTER TABLE report_audit_logs
    ADD COLUMN IF NOT EXISTS provider_name VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS prompt_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS violation_codes TEXT,
    ADD COLUMN IF NOT EXISTS input_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS output_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(80),
    ADD COLUMN IF NOT EXISTS latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS anonymize_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delete_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS anonymized_at TIMESTAMPTZ;

ALTER TABLE resume_audit_logs
    ADD COLUMN IF NOT EXISTS provider_name VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS prompt_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS violation_codes TEXT,
    ADD COLUMN IF NOT EXISTS input_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS output_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(80),
    ADD COLUMN IF NOT EXISTS latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS anonymize_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delete_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS anonymized_at TIMESTAMPTZ;

UPDATE query_audit_logs
SET anonymize_after = COALESCE(anonymize_after, created_at + INTERVAL '7 days'),
    delete_after = COALESCE(delete_after, created_at + INTERVAL '30 days');
UPDATE knowledge_qa_audit_logs
SET anonymize_after = COALESCE(anonymize_after, created_at + INTERVAL '7 days'),
    delete_after = COALESCE(delete_after, created_at + INTERVAL '30 days');
UPDATE support_audit_logs
SET anonymize_after = COALESCE(anonymize_after, created_at + INTERVAL '7 days'),
    delete_after = COALESCE(delete_after, created_at + INTERVAL '30 days');
UPDATE report_audit_logs
SET anonymize_after = COALESCE(anonymize_after, created_at + INTERVAL '7 days'),
    delete_after = COALESCE(delete_after, created_at + INTERVAL '30 days');
UPDATE resume_audit_logs
SET anonymize_after = COALESCE(anonymize_after, created_at + INTERVAL '7 days'),
    delete_after = COALESCE(delete_after, created_at + INTERVAL '30 days');

ALTER TABLE query_audit_logs
    ALTER COLUMN anonymize_after SET DEFAULT (now() + INTERVAL '7 days'),
    ALTER COLUMN delete_after SET DEFAULT (now() + INTERVAL '30 days');
ALTER TABLE knowledge_qa_audit_logs
    ALTER COLUMN anonymize_after SET DEFAULT (now() + INTERVAL '7 days'),
    ALTER COLUMN delete_after SET DEFAULT (now() + INTERVAL '30 days');
ALTER TABLE support_audit_logs
    ALTER COLUMN anonymize_after SET DEFAULT (now() + INTERVAL '7 days'),
    ALTER COLUMN delete_after SET DEFAULT (now() + INTERVAL '30 days');
ALTER TABLE report_audit_logs
    ALTER COLUMN anonymize_after SET DEFAULT (now() + INTERVAL '7 days'),
    ALTER COLUMN delete_after SET DEFAULT (now() + INTERVAL '30 days');
ALTER TABLE resume_audit_logs
    ALTER COLUMN anonymize_after SET DEFAULT (now() + INTERVAL '7 days'),
    ALTER COLUMN delete_after SET DEFAULT (now() + INTERVAL '30 days');

CREATE INDEX IF NOT EXISTS idx_query_audit_retention
    ON query_audit_logs(delete_after, anonymize_after);
CREATE INDEX IF NOT EXISTS idx_knowledge_audit_retention
    ON knowledge_qa_audit_logs(delete_after, anonymize_after);
CREATE INDEX IF NOT EXISTS idx_support_audit_retention
    ON support_audit_logs(delete_after, anonymize_after);
CREATE INDEX IF NOT EXISTS idx_report_audit_retention
    ON report_audit_logs(delete_after, anonymize_after);
CREATE INDEX IF NOT EXISTS idx_resume_audit_retention
    ON resume_audit_logs(delete_after, anonymize_after);
