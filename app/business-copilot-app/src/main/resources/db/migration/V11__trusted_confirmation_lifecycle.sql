-- V11: bind every high-risk pending object to an owner, token digest, state, and expiry.
-- Existing v1.1 plaintext tokens are intentionally invalidated instead of migrated.

CREATE TABLE IF NOT EXISTS data_sql_candidates (
    candidate_id      VARCHAR(64) PRIMARY KEY,
    sql_text          TEXT NOT NULL,
    token_digest      VARCHAR(64),
    status            VARCHAR(32) NOT NULL,
    owner_actor_id    VARCHAR(100) NOT NULL,
    request_id        VARCHAR(64),
    model_name        VARCHAR(100),
    prompt_name       VARCHAR(200),
    prompt_version    VARCHAR(64),
    prompt_hash       VARCHAR(64),
    expires_at        TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    action_actor_id   VARCHAR(100),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_data_sql_candidates_owner_status
    ON data_sql_candidates(owner_actor_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_data_sql_candidates_expires_at
    ON data_sql_candidates(expires_at) WHERE token_digest IS NOT NULL;

ALTER TABLE support_tickets
    ADD COLUMN IF NOT EXISTS owner_actor_id VARCHAR(100);
UPDATE support_tickets SET owner_actor_id = 'system' WHERE owner_actor_id IS NULL;
ALTER TABLE support_tickets ALTER COLUMN owner_actor_id SET NOT NULL;

ALTER TABLE support_reply_drafts
    ADD COLUMN IF NOT EXISTS owner_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS confirmation_token_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS review_queue BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reviewer_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE support_reply_drafts
SET owner_actor_id = 'system',
    status = 'DRAFTED',
    confirmation_token = NULL,
    confirmation_token_digest = NULL
WHERE owner_actor_id IS NULL OR status IS NULL OR confirmation_token IS NOT NULL;
ALTER TABLE support_reply_drafts ALTER COLUMN owner_actor_id SET NOT NULL;
ALTER TABLE support_reply_drafts ALTER COLUMN status SET NOT NULL;
DROP INDEX IF EXISTS idx_support_reply_drafts_confirmation_token;
CREATE INDEX IF NOT EXISTS idx_support_reply_drafts_owner_status
    ON support_reply_drafts(owner_actor_id, status, created_at DESC);

ALTER TABLE report_requests
    ADD COLUMN IF NOT EXISTS owner_actor_id VARCHAR(100);
UPDATE report_requests SET owner_actor_id = 'system' WHERE owner_actor_id IS NULL;
ALTER TABLE report_requests ALTER COLUMN owner_actor_id SET NOT NULL;

ALTER TABLE report_drafts
    ADD COLUMN IF NOT EXISTS owner_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS confirmation_token_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100);
UPDATE report_drafts d
SET owner_actor_id = r.owner_actor_id,
    confirmation_token = NULL,
    confirmation_token_digest = NULL
FROM report_requests r
WHERE d.request_id = r.id
  AND (d.owner_actor_id IS NULL OR d.confirmation_token IS NOT NULL);
ALTER TABLE report_drafts ALTER COLUMN owner_actor_id SET NOT NULL;
DROP INDEX IF EXISTS idx_report_drafts_confirmation_token;
CREATE INDEX IF NOT EXISTS idx_report_drafts_owner_status
    ON report_drafts(owner_actor_id, status, created_at DESC);

ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS owner_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS criteria_token_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100);
UPDATE resume_jobs
SET owner_actor_id = 'system',
    criteria_token = NULL,
    criteria_token_digest = NULL
WHERE owner_actor_id IS NULL OR criteria_token IS NOT NULL;
ALTER TABLE resume_jobs ALTER COLUMN owner_actor_id SET NOT NULL;
DROP INDEX IF EXISTS idx_resume_jobs_criteria_token;
CREATE INDEX IF NOT EXISTS idx_resume_jobs_owner_status
    ON resume_jobs(owner_actor_id, status, created_at DESC);

ALTER TABLE resume_assessments
    ADD COLUMN IF NOT EXISTS owner_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS review_token_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS review_queue BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reviewer_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100);
UPDATE resume_assessments a
SET owner_actor_id = j.owner_actor_id,
    review_token = NULL,
    review_token_digest = NULL
FROM resume_jobs j
WHERE a.job_id = j.id
  AND (a.owner_actor_id IS NULL OR a.review_token IS NOT NULL);
ALTER TABLE resume_assessments ALTER COLUMN owner_actor_id SET NOT NULL;
DROP INDEX IF EXISTS idx_resume_assessments_review_token;
CREATE INDEX IF NOT EXISTS idx_resume_assessments_owner_status
    ON resume_assessments(owner_actor_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resume_assessments_review_queue
    ON resume_assessments(review_queue, reviewer_actor_id, status)
    WHERE review_queue = TRUE;

ALTER TABLE query_audit_logs
    ADD COLUMN IF NOT EXISTS creator_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100);
ALTER TABLE support_audit_logs
    ADD COLUMN IF NOT EXISTS creator_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100);
ALTER TABLE report_audit_logs
    ADD COLUMN IF NOT EXISTS creator_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100);
ALTER TABLE resume_audit_logs
    ADD COLUMN IF NOT EXISTS creator_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_actor_id VARCHAR(100);
