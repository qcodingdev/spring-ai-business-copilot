-- V6: tables reserved for the Report Copilot draft lifecycle.
-- report_sources and report_audit_logs must never store raw, unmasked user input or full report text.

CREATE TABLE IF NOT EXISTS report_requests (
    id           BIGSERIAL PRIMARY KEY,
    report_type  VARCHAR(50) NOT NULL,
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,
    title        VARCHAR(300) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS report_sources (
    id                BIGSERIAL PRIMARY KEY,
    request_id        BIGINT NOT NULL REFERENCES report_requests(id) ON DELETE CASCADE,
    source_type       VARCHAR(50) NOT NULL,
    source_ref        VARCHAR(200),
    source_title      VARCHAR(300) NOT NULL,
    sanitized_content TEXT NOT NULL,
    source_hash       VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_report_sources_request_id ON report_sources(request_id);

CREATE TABLE IF NOT EXISTS report_drafts (
    id                 BIGSERIAL PRIMARY KEY,
    request_id         BIGINT NOT NULL REFERENCES report_requests(id) ON DELETE CASCADE,
    structured_content TEXT NOT NULL,
    cited_source_ids   TEXT,
    status             VARCHAR(30) NOT NULL,
    review_reasons     TEXT,
    confirmation_token VARCHAR(200),
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_report_drafts_request_id ON report_drafts(request_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_report_drafts_confirmation_token
    ON report_drafts(confirmation_token) WHERE confirmation_token IS NOT NULL;

CREATE TABLE IF NOT EXISTS report_audit_logs (
    id               BIGSERIAL PRIMARY KEY,
    request_id       BIGINT,
    draft_id         BIGINT,
    event_type       VARCHAR(50) NOT NULL,
    source_types     TEXT,
    source_count     INTEGER,
    cited_source_ids TEXT,
    model_name       VARCHAR(100),
    latency_ms       BIGINT,
    status           VARCHAR(30),
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_report_audit_logs_created_at ON report_audit_logs(created_at DESC);
