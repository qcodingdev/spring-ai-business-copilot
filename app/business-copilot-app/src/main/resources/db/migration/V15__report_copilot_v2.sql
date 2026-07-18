-- V15: immutable, versioned Report Copilot source snapshots and template metadata.

ALTER TABLE report_requests
    ADD COLUMN IF NOT EXISTS template_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS template_version VARCHAR(50);

UPDATE report_requests
SET template_id = 'evidence-weekly',
    template_version = '2.0'
WHERE template_id IS NULL OR template_version IS NULL;

ALTER TABLE report_requests
    ALTER COLUMN template_id SET NOT NULL,
    ALTER COLUMN template_version SET NOT NULL;

ALTER TABLE report_sources
    ADD COLUMN IF NOT EXISTS snapshot_id UUID,
    ADD COLUMN IF NOT EXISTS provider_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_version VARCHAR(100),
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS source_timezone VARCHAR(80),
    ADD COLUMN IF NOT EXISTS source_unit VARCHAR(80),
    ADD COLUMN IF NOT EXISTS valid_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS freshness_status VARCHAR(20);

UPDATE report_sources
SET snapshot_id = gen_random_uuid(),
    provider_id = 'legacy',
    source_version = '1',
    observed_at = created_at,
    source_timezone = 'UTC',
    source_unit = '',
    freshness_status = 'UNKNOWN'
WHERE snapshot_id IS NULL;

ALTER TABLE report_sources
    ALTER COLUMN snapshot_id SET NOT NULL,
    ALTER COLUMN provider_id SET NOT NULL,
    ALTER COLUMN source_version SET NOT NULL,
    ALTER COLUMN observed_at SET NOT NULL,
    ALTER COLUMN source_timezone SET NOT NULL,
    ALTER COLUMN source_unit SET NOT NULL,
    ALTER COLUMN freshness_status SET NOT NULL;

ALTER TABLE report_sources
    DROP CONSTRAINT IF EXISTS chk_report_sources_freshness;
ALTER TABLE report_sources
    ADD CONSTRAINT chk_report_sources_freshness
        CHECK (freshness_status IN ('FRESH', 'STALE', 'UNKNOWN'));

CREATE UNIQUE INDEX IF NOT EXISTS idx_report_sources_snapshot_id
    ON report_sources(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_report_sources_provider_version
    ON report_sources(provider_id, source_version);
CREATE INDEX IF NOT EXISTS idx_report_sources_freshness
    ON report_sources(freshness_status, valid_until);

CREATE OR REPLACE FUNCTION reject_report_source_snapshot_update()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'report source snapshots are immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_report_sources_immutable ON report_sources;
CREATE TRIGGER trg_report_sources_immutable
    BEFORE UPDATE ON report_sources
    FOR EACH ROW
    EXECUTE FUNCTION reject_report_source_snapshot_update();
