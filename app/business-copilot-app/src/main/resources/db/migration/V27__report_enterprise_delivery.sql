-- V27：Report Copilot 企业来源、定时待确认草稿、异常和办公格式导出。

CREATE TABLE report_external_connections (
    id                  BIGSERIAL PRIMARY KEY,
    connection_key      VARCHAR(100) NOT NULL UNIQUE,
    display_name        VARCHAR(200) NOT NULL,
    provider            VARCHAR(40) NOT NULL,
    base_url            VARCHAR(500),
    secret_ref          VARCHAR(200),
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    owner_actor_id      VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_external_provider CHECK (provider IN
        ('JIRA', 'MEETING_NOTES', 'DATA_QUERY', 'SUPPORT_METRICS'))
);

CREATE TABLE report_schedules (
    id                  BIGSERIAL PRIMARY KEY,
    schedule_key        VARCHAR(100) NOT NULL UNIQUE,
    report_type         VARCHAR(40) NOT NULL,
    title_template      VARCHAR(300) NOT NULL,
    cron_expression     VARCHAR(100) NOT NULL,
    zone_id             VARCHAR(80) NOT NULL,
    template_id         VARCHAR(100) NOT NULL,
    template_version    VARCHAR(40) NOT NULL,
    source_config       JSONB NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    owner_actor_id      VARCHAR(100) NOT NULL,
    last_run_at         TIMESTAMPTZ,
    next_run_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE report_schedule_runs (
    id                  BIGSERIAL PRIMARY KEY,
    schedule_id         BIGINT NOT NULL REFERENCES report_schedules(id) ON DELETE CASCADE,
    report_draft_id     BIGINT REFERENCES report_drafts(id),
    status              VARCHAR(32) NOT NULL,
    reason              VARCHAR(500),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    CONSTRAINT ck_report_schedule_run_status CHECK (status IN
        ('RUNNING', 'DRAFTED', 'NEEDS_REVIEW', 'FAILED', 'SKIPPED'))
);

ALTER TABLE report_requests
    ADD COLUMN IF NOT EXISTS comparison_period_start DATE,
    ADD COLUMN IF NOT EXISTS comparison_period_end DATE,
    ADD COLUMN IF NOT EXISTS anomaly_summary JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE report_export_audit (
    id                  BIGSERIAL PRIMARY KEY,
    draft_id            BIGINT NOT NULL REFERENCES report_drafts(id) ON DELETE CASCADE,
    export_format       VARCHAR(20) NOT NULL,
    exported_by         VARCHAR(100) NOT NULL,
    content_hash        VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_export_format CHECK (export_format IN
        ('MARKDOWN', 'HTML', 'DOCX', 'PDF', 'PPTX'))
);

CREATE INDEX idx_report_export_audit_draft
    ON report_export_audit(draft_id, created_at DESC);
