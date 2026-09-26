ALTER TABLE evaluation_runs
    ADD COLUMN heartbeat_at TIMESTAMPTZ;

UPDATE evaluation_runs
SET heartbeat_at = COALESCE(started_at, created_at)
WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_evaluation_runs_recovery
    ON evaluation_runs(status, heartbeat_at)
    WHERE status IN ('QUEUED', 'RUNNING');
