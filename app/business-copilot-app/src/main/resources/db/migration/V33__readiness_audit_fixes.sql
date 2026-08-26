-- V33: readiness prerequisite semantics, sync serialization, and probe indexes.

ALTER TABLE enterprise_readiness_snapshots
    DROP CONSTRAINT ck_enterprise_readiness_status;
ALTER TABLE enterprise_readiness_snapshots
    ADD CONSTRAINT ck_enterprise_readiness_status
        CHECK (status IN ('READY', 'ATTENTION', 'BLOCKED', 'NOT_CONFIGURED'));

ALTER TABLE report_drafts
    ADD COLUMN review_due_at TIMESTAMPTZ;
UPDATE report_drafts
SET review_due_at = created_at + interval '24 hours'
WHERE review_due_at IS NULL;
ALTER TABLE report_drafts
    ALTER COLUMN review_due_at SET NOT NULL,
    ALTER COLUMN review_due_at SET DEFAULT (now() + interval '24 hours');

ALTER TABLE resume_assessments
    ADD COLUMN review_due_at TIMESTAMPTZ;
UPDATE resume_assessments
SET review_due_at = created_at + interval '24 hours'
WHERE review_due_at IS NULL;
ALTER TABLE resume_assessments
    ALTER COLUMN review_due_at SET NOT NULL,
    ALTER COLUMN review_due_at SET DEFAULT (now() + interval '24 hours');

ALTER TABLE hr_onboarding_tasks
    ADD COLUMN due_at TIMESTAMPTZ;
UPDATE hr_onboarding_tasks
SET due_at = created_at + interval '1 day'
WHERE due_at IS NULL;
ALTER TABLE hr_onboarding_tasks
    ALTER COLUMN due_at SET NOT NULL,
    ALTER COLUMN due_at SET DEFAULT (now() + interval '1 day');

-- Preserve the newest active run if an older deployment already admitted duplicates.
WITH ranked_active_runs AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY connection_id ORDER BY started_at DESC, id DESC
           ) AS active_rank
    FROM knowledge_sync_runs
    WHERE status IN ('PENDING', 'RUNNING')
)
UPDATE knowledge_sync_runs run
SET status = 'CANCELED',
    finished_at = COALESCE(run.finished_at, now()),
    error_category = COALESCE(run.error_category, 'CONCURRENT_RUN_RECONCILED')
FROM ranked_active_runs ranked
WHERE run.id = ranked.id
  AND ranked.active_rank > 1;

CREATE UNIQUE INDEX uk_knowledge_sync_runs_active_connection
    ON knowledge_sync_runs(connection_id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_knowledge_sync_runs_connection_status_started
    ON knowledge_sync_runs(connection_id, status, started_at DESC);
CREATE INDEX idx_knowledge_sync_runs_running_started
    ON knowledge_sync_runs(started_at)
    WHERE status = 'RUNNING';

CREATE INDEX idx_support_writebacks_processing_age
    ON support_draft_writebacks (
        (COALESCE(last_attempt_at, updated_at, created_at))
    ) WHERE status = 'PROCESSING';
CREATE INDEX idx_support_tickets_open_breached
    ON support_tickets(id)
    WHERE sla_status = 'BREACHED' AND status NOT IN ('CLOSED', 'CANCELED');

CREATE INDEX idx_report_schedule_runs_schedule_status_started
    ON report_schedule_runs(schedule_id, status, started_at DESC);
CREATE INDEX idx_report_drafts_review_backlog
    ON report_drafts(review_due_at)
    WHERE status IN ('DRAFTED', 'NEEDS_REVIEW');
CREATE INDEX idx_resume_assessments_review_backlog
    ON resume_assessments(review_due_at)
    WHERE status IN ('DRAFTED', 'NEEDS_REVIEW');
CREATE INDEX idx_hr_onboarding_tasks_required_pending
    ON hr_onboarding_tasks(due_at, instance_id)
    WHERE required = TRUE AND status = 'PENDING';
