-- Preserve restart context and version-scoped, append-only acceptance history.
ALTER TABLE task_runs ADD COLUMN context_scope_refs JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE acceptance_evidence DROP CONSTRAINT uq_acceptance_category_name;
CREATE INDEX idx_acceptance_version_history
    ON acceptance_evidence (applicable_version, category, name, recorded_at DESC, id DESC);
