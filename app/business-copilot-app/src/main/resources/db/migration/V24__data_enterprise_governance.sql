-- V24：Data Copilot 企业指标治理、批准模板、schema 漂移和结果交接。

CREATE TABLE data_metric_definitions (
    id                  BIGSERIAL PRIMARY KEY,
    metric_key          VARCHAR(100) NOT NULL UNIQUE,
    display_name        VARCHAR(200) NOT NULL,
    description         VARCHAR(1000) NOT NULL,
    unit                VARCHAR(50),
    expression_sql      TEXT NOT NULL,
    owner_actor_id      VARCHAR(100) NOT NULL,
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    active              BOOLEAN NOT NULL DEFAULT FALSE,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_data_metric_version CHECK (version >= 1)
);

CREATE TABLE data_query_templates (
    id                  BIGSERIAL PRIMARY KEY,
    template_key        VARCHAR(100) NOT NULL,
    name                VARCHAR(200) NOT NULL,
    description         VARCHAR(1000) NOT NULL,
    sql_text            TEXT NOT NULL,
    parameter_schema    JSONB NOT NULL DEFAULT '{}'::jsonb,
    owner_actor_id      VARCHAR(100) NOT NULL,
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    active              BOOLEAN NOT NULL DEFAULT FALSE,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_data_query_template_version UNIQUE (template_key, version),
    CONSTRAINT ck_data_query_template_version CHECK (version >= 1)
);

CREATE TABLE data_schema_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    source_name         VARCHAR(100) NOT NULL,
    schema_hash         VARCHAR(64) NOT NULL,
    schema_json         JSONB NOT NULL,
    change_summary      JSONB NOT NULL DEFAULT '[]'::jsonb,
    checked_by          VARCHAR(100) NOT NULL,
    checked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed             BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_data_schema_snapshots_source_checked
    ON data_schema_snapshots(source_name, checked_at DESC);

CREATE TABLE data_query_results (
    id                  BIGSERIAL PRIMARY KEY,
    candidate_id        VARCHAR(64) NOT NULL REFERENCES data_sql_candidates(candidate_id),
    owner_actor_id      VARCHAR(100) NOT NULL,
    columns_json        JSONB NOT NULL,
    rows_json           JSONB NOT NULL,
    row_count           INTEGER NOT NULL,
    truncated           BOOLEAN NOT NULL,
    explanation_json    JSONB,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_data_query_results_candidate UNIQUE (candidate_id),
    CONSTRAINT ck_data_query_result_row_count CHECK (row_count >= 0)
);

CREATE INDEX idx_data_query_results_owner_created
    ON data_query_results(owner_actor_id, created_at DESC);
CREATE INDEX idx_data_query_results_expiry
    ON data_query_results(expires_at);

CREATE TABLE data_report_handoffs (
    id                  BIGSERIAL PRIMARY KEY,
    query_result_id     BIGINT NOT NULL REFERENCES data_query_results(id) ON DELETE CASCADE,
    owner_actor_id      VARCHAR(100) NOT NULL,
    title               VARCHAR(300) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'READY',
    source_reference    VARCHAR(100) NOT NULL UNIQUE,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_data_report_handoff_status
        CHECK (status IN ('READY', 'CONSUMED', 'EXPIRED'))
);

CREATE INDEX idx_data_report_handoffs_owner_status
    ON data_report_handoffs(owner_actor_id, status, created_at DESC);
