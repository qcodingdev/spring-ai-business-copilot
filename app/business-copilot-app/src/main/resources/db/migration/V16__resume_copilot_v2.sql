-- V16: versioned job criteria, bounded resume retention, and reviewer feedback.

ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS logical_job_id UUID,
    ADD COLUMN IF NOT EXISTS criteria_version INTEGER,
    ADD COLUMN IF NOT EXISTS current_version BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS effective_from TIMESTAMPTZ;

UPDATE resume_jobs
SET logical_job_id = gen_random_uuid(),
    criteria_version = 1,
    effective_from = created_at
WHERE logical_job_id IS NULL OR criteria_version IS NULL OR effective_from IS NULL;

ALTER TABLE resume_jobs
    ALTER COLUMN logical_job_id SET NOT NULL,
    ALTER COLUMN criteria_version SET NOT NULL,
    ALTER COLUMN effective_from SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_jobs_logical_version
    ON resume_jobs(logical_job_id, criteria_version);
CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_jobs_one_current_version
    ON resume_jobs(logical_job_id)
    WHERE current_version = TRUE;

ALTER TABLE resume_submissions
    ADD COLUMN IF NOT EXISTS source_file_name VARCHAR(300),
    ADD COLUMN IF NOT EXISTS source_content_type VARCHAR(150),
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

UPDATE resume_submissions
SET source_file_name = COALESCE(source_file_name, 'text-input.txt'),
    source_content_type = COALESCE(source_content_type, 'text/plain'),
    expires_at = COALESCE(expires_at, created_at + INTERVAL '30 days')
WHERE source_file_name IS NULL OR source_content_type IS NULL OR expires_at IS NULL;

ALTER TABLE resume_submissions
    ALTER COLUMN source_file_name SET NOT NULL,
    ALTER COLUMN source_content_type SET NOT NULL,
    ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_resume_submissions_expiry
    ON resume_submissions(expires_at)
    WHERE deleted_at IS NULL;

ALTER TABLE resume_assessments
    ADD COLUMN IF NOT EXISTS criteria_version INTEGER,
    ADD COLUMN IF NOT EXISTS original_content_json TEXT,
    ADD COLUMN IF NOT EXISTS corrected_content_json TEXT,
    ADD COLUMN IF NOT EXISTS reviewer_feedback TEXT,
    ADD COLUMN IF NOT EXISTS decision_outcome VARCHAR(30),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

UPDATE resume_assessments assessment
SET criteria_version = job.criteria_version,
    original_content_json = assessment.content_json,
    decision_outcome = CASE
        WHEN assessment.decision_outcome IS NOT NULL THEN assessment.decision_outcome
        WHEN assessment.status = 'REVIEWED' THEN 'ACCEPTED'
        WHEN assessment.status = 'CANCELED' THEN 'REJECTED'
        ELSE NULL
    END,
    reviewed_at = CASE
        WHEN assessment.reviewed_at IS NOT NULL THEN assessment.reviewed_at
        WHEN assessment.status = 'REVIEWED' THEN assessment.updated_at
        ELSE NULL
    END
FROM resume_jobs job
WHERE assessment.job_id = job.id
  AND (assessment.criteria_version IS NULL
       OR assessment.original_content_json IS NULL
       OR (assessment.status IN ('REVIEWED', 'CANCELED') AND assessment.decision_outcome IS NULL));

ALTER TABLE resume_assessments
    ALTER COLUMN criteria_version SET NOT NULL,
    ALTER COLUMN original_content_json SET NOT NULL;

ALTER TABLE resume_assessments
    DROP CONSTRAINT IF EXISTS chk_resume_assessment_outcome;
ALTER TABLE resume_assessments
    ADD CONSTRAINT chk_resume_assessment_outcome
        CHECK (decision_outcome IS NULL OR decision_outcome IN
            ('ACCEPTED', 'EDITED_ACCEPTED', 'REJECTED'));

CREATE INDEX IF NOT EXISTS idx_resume_assessments_criteria_version
    ON resume_assessments(job_id, criteria_version);
