-- V32: immutable, content-safe evidence for the five-module enterprise-readiness loop.

CREATE TABLE enterprise_readiness_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    snapshot_reference  UUID NOT NULL UNIQUE,
    schema_version      INTEGER NOT NULL DEFAULT 1,
    purpose             VARCHAR(200) NOT NULL,
    application_version VARCHAR(64) NOT NULL,
    runtime_mode        VARCHAR(32) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    passed_count        INTEGER NOT NULL,
    warning_count       INTEGER NOT NULL,
    blocker_count       INTEGER NOT NULL,
    checks_json         JSONB NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    generated_by        VARCHAR(100) NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL,
    valid_until         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_enterprise_readiness_schema_version CHECK (schema_version >= 1),
    CONSTRAINT ck_enterprise_readiness_purpose CHECK (length(btrim(purpose)) > 0),
    CONSTRAINT ck_enterprise_readiness_status CHECK (status IN ('READY', 'ATTENTION', 'BLOCKED')),
    CONSTRAINT ck_enterprise_readiness_counts CHECK (
        passed_count >= 0 AND warning_count >= 0 AND blocker_count >= 0
        AND jsonb_typeof(checks_json) = 'array'
        AND passed_count + warning_count + blocker_count = jsonb_array_length(checks_json)
    ),
    CONSTRAINT ck_enterprise_readiness_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_enterprise_readiness_validity CHECK (valid_until > generated_at)
);

CREATE INDEX idx_enterprise_readiness_generated
    ON enterprise_readiness_snapshots(generated_at DESC);
CREATE INDEX idx_enterprise_readiness_status_generated
    ON enterprise_readiness_snapshots(status, generated_at DESC);

CREATE FUNCTION reject_enterprise_readiness_snapshot_update()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'enterprise readiness snapshots are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enterprise_readiness_snapshot_immutable
    BEFORE UPDATE ON enterprise_readiness_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_enterprise_readiness_snapshot_update();
