-- V7: Resume Copilot stores only sanitized inputs and metadata.
-- Raw resumes, contact details, protected attributes, full model responses, and review tokens never enter audit logs.

CREATE TABLE IF NOT EXISTS resume_jobs (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(300) NOT NULL,
    sanitized_jd    TEXT NOT NULL,
    criteria_json   TEXT NOT NULL,
    status          VARCHAR(40) NOT NULL,
    criteria_token  VARCHAR(200),
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_jobs_criteria_token
    ON resume_jobs(criteria_token) WHERE criteria_token IS NOT NULL;

CREATE TABLE IF NOT EXISTS resume_submissions (
    id                     BIGSERIAL PRIMARY KEY,
    job_id                 BIGINT NOT NULL REFERENCES resume_jobs(id) ON DELETE CASCADE,
    anonymous_candidate_id VARCHAR(80) NOT NULL,
    sanitized_resume       TEXT NOT NULL,
    content_hash           VARCHAR(64) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS resume_evidence (
    id             BIGSERIAL PRIMARY KEY,
    submission_id  BIGINT NOT NULL REFERENCES resume_submissions(id) ON DELETE CASCADE,
    evidence_ref   VARCHAR(80) NOT NULL,
    section_name   VARCHAR(100) NOT NULL,
    sanitized_text TEXT NOT NULL,
    position_index INTEGER NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (submission_id, evidence_ref)
);

CREATE TABLE IF NOT EXISTS resume_assessments (
    id               BIGSERIAL PRIMARY KEY,
    job_id           BIGINT NOT NULL REFERENCES resume_jobs(id) ON DELETE CASCADE,
    submission_id    BIGINT NOT NULL REFERENCES resume_submissions(id) ON DELETE CASCADE,
    content_json     TEXT NOT NULL,
    status           VARCHAR(40) NOT NULL,
    review_reasons   TEXT,
    review_token     VARCHAR(200),
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_assessments_review_token
    ON resume_assessments(review_token) WHERE review_token IS NOT NULL;

CREATE TABLE IF NOT EXISTS resume_audit_logs (
    id               BIGSERIAL PRIMARY KEY,
    request_id       VARCHAR(80),
    job_id           BIGINT,
    submission_id    BIGINT,
    assessment_id    BIGINT,
    event_type       VARCHAR(50) NOT NULL,
    criteria_count   INTEGER NOT NULL DEFAULT 0,
    evidence_count   INTEGER NOT NULL DEFAULT 0,
    model_name       VARCHAR(100),
    status           VARCHAR(40),
    error_message    VARCHAR(500),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_resume_audit_logs_created_at ON resume_audit_logs(created_at DESC);
