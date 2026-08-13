-- V31: immutable governance versions, claim-safe handoffs/schedules, recoverable writeback,
-- interview membership, and actionable onboarding instances.

ALTER TABLE data_metric_definitions
    DROP CONSTRAINT IF EXISTS data_metric_definitions_metric_key_key;
ALTER TABLE data_metric_definitions
    ADD CONSTRAINT uk_data_metric_version UNIQUE (metric_key, version);
WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY metric_key ORDER BY version DESC, updated_at DESC, id DESC
    ) AS position
    FROM data_metric_definitions
    WHERE active = TRUE
)
UPDATE data_metric_definitions definition
SET active = FALSE, updated_at = now()
FROM ranked
WHERE definition.id = ranked.id AND ranked.position > 1;
WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY template_key ORDER BY version DESC, updated_at DESC, id DESC
    ) AS position
    FROM data_query_templates
    WHERE active = TRUE
)
UPDATE data_query_templates template
SET active = FALSE, updated_at = now()
FROM ranked
WHERE template.id = ranked.id AND ranked.position > 1;
CREATE UNIQUE INDEX uk_data_metric_one_active
    ON data_metric_definitions(metric_key) WHERE active = TRUE;
CREATE UNIQUE INDEX uk_data_template_one_active
    ON data_query_templates(template_key) WHERE active = TRUE;

CREATE TABLE data_governance_actions (
    id BIGSERIAL PRIMARY KEY,
    object_type VARCHAR(40) NOT NULL,
    object_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    actor_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_data_governance_object CHECK (object_type IN ('METRIC', 'QUERY_TEMPLATE')),
    CONSTRAINT ck_data_governance_action CHECK (action IN ('APPROVED', 'DEACTIVATED'))
);

ALTER TABLE data_report_handoffs
    DROP CONSTRAINT ck_data_report_handoff_status,
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_data_report_handoff_status
        CHECK (status IN ('READY', 'CLAIMED', 'CONSUMED', 'EXPIRED'));
CREATE INDEX idx_data_report_handoffs_claim ON data_report_handoffs(claim_token)
    WHERE status = 'CLAIMED';

ALTER TABLE report_schedules
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ;
CREATE INDEX idx_report_schedules_due_claim
    ON report_schedules(next_run_at, claimed_at) WHERE enabled = TRUE;

-- DATA_QUERY and SUPPORT_METRICS were placeholders rather than callable external
-- adapters. Keep their historical definitions for traceability, but make it
-- impossible for them to remain active after the formal 2.3 upgrade.
UPDATE report_external_connections
SET enabled = FALSE, updated_at = now()
WHERE provider IN ('DATA_QUERY', 'SUPPORT_METRICS');
ALTER TABLE report_external_connections
    DROP CONSTRAINT ck_report_external_provider,
    ADD CONSTRAINT ck_report_external_provider CHECK (
        provider IN ('JIRA', 'MEETING_NOTES')
        OR (provider IN ('DATA_QUERY', 'SUPPORT_METRICS') AND enabled = FALSE)
    );

-- A schedule must be repeatable. Historical schedules that consumed a one-shot
-- Data handoff, or selected a deprecated connection, are retained but disabled
-- so an administrator can replace their source configuration explicitly.
UPDATE report_schedules schedule
SET enabled = FALSE, updated_at = now()
WHERE jsonb_array_length(
          CASE
              WHEN jsonb_typeof(schedule.source_config -> 'dataHandoffReferences') = 'array'
                  THEN schedule.source_config -> 'dataHandoffReferences'
              ELSE '[]'::jsonb
          END
      ) > 0
   OR COALESCE(BTRIM(schedule.source_config ->> 'previousDataHandoffReference'), '') <> ''
   OR (
       jsonb_array_length(
           CASE
               WHEN jsonb_typeof(schedule.source_config -> 'connectionIds') = 'array'
                   THEN schedule.source_config -> 'connectionIds'
               ELSE '[]'::jsonb
           END
       ) = 0
       AND LOWER(COALESCE(schedule.source_config ->> 'includeSupportMetrics', 'false')) <> 'true'
   )
   OR EXISTS (
       SELECT 1
       FROM jsonb_array_elements_text(
           CASE
               WHEN jsonb_typeof(schedule.source_config -> 'connectionIds') = 'array'
                   THEN schedule.source_config -> 'connectionIds'
               ELSE '[]'::jsonb
           END
       ) AS selected(connection_id)
       WHERE NOT EXISTS (
           SELECT 1
           FROM report_external_connections connection
           WHERE connection.id::text = selected.connection_id
             AND connection.enabled = TRUE
             AND connection.provider IN ('JIRA', 'MEETING_NOTES')
       )
   );
ALTER TABLE report_schedules
    ADD CONSTRAINT ck_report_schedule_repeatable_source CHECK (
        enabled = FALSE OR (
            jsonb_array_length(
                CASE
                    WHEN jsonb_typeof(source_config -> 'dataHandoffReferences') = 'array'
                        THEN source_config -> 'dataHandoffReferences'
                    ELSE '[]'::jsonb
                END
            ) = 0
            AND COALESCE(BTRIM(source_config ->> 'previousDataHandoffReference'), '') = ''
            AND (
                LOWER(COALESCE(source_config ->> 'includeSupportMetrics', 'false')) = 'true'
                OR jsonb_array_length(
                    CASE
                        WHEN jsonb_typeof(source_config -> 'connectionIds') = 'array'
                            THEN source_config -> 'connectionIds'
                        ELSE '[]'::jsonb
                    END
                ) > 0
            )
        )
    );

ALTER TABLE support_draft_writebacks
    DROP CONSTRAINT IF EXISTS ck_support_writeback_status,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN external_receipt VARCHAR(500);
UPDATE support_draft_writebacks
SET status = 'UNKNOWN', token_digest = NULL,
    error_category = 'LEGACY_EXTERNAL_OUTCOME_UNKNOWN', updated_at = now()
WHERE status = 'CONFIRMED';
ALTER TABLE support_draft_writebacks
    ADD CONSTRAINT ck_support_writeback_status CHECK (status IN
        ('PENDING_CONFIRMATION', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN',
         'EXPIRED', 'CANCELED'));

-- V26 used a read-before-insert import path. Consolidate any rows produced by a
-- historical race before enforcing the stable external ticket identity.
WITH duplicate AS (
    SELECT id, min(id) OVER (
        PARTITION BY external_connection_id, external_id
    ) AS keeper_id
    FROM support_tickets
    WHERE external_connection_id IS NOT NULL AND external_id IS NOT NULL
)
UPDATE support_reply_drafts draft
SET ticket_id = duplicate.keeper_id
FROM duplicate
WHERE draft.ticket_id = duplicate.id AND duplicate.id <> duplicate.keeper_id;
WITH duplicate AS (
    SELECT id, min(id) OVER (
        PARTITION BY external_connection_id, external_id
    ) AS keeper_id
    FROM support_tickets
    WHERE external_connection_id IS NOT NULL AND external_id IS NOT NULL
)
UPDATE support_ticket_context_snapshots snapshot
SET ticket_id = duplicate.keeper_id
FROM duplicate
WHERE snapshot.ticket_id = duplicate.id AND duplicate.id <> duplicate.keeper_id;
WITH duplicate AS (
    SELECT id, min(id) OVER (
        PARTITION BY external_connection_id, external_id
    ) AS keeper_id
    FROM support_tickets
    WHERE external_connection_id IS NOT NULL AND external_id IS NOT NULL
)
UPDATE support_audit_logs audit
SET ticket_id = duplicate.keeper_id
FROM duplicate
WHERE audit.ticket_id = duplicate.id AND duplicate.id <> duplicate.keeper_id;
WITH duplicate AS (
    SELECT id, min(id) OVER (
        PARTITION BY external_connection_id, external_id
    ) AS keeper_id
    FROM support_tickets
    WHERE external_connection_id IS NOT NULL AND external_id IS NOT NULL
)
DELETE FROM support_tickets ticket
USING duplicate
WHERE ticket.id = duplicate.id AND duplicate.id <> duplicate.keeper_id;

WITH duplicate_context AS (
    SELECT id, row_number() OVER (
        PARTITION BY ticket_id, context_type, source_reference, observed_at
        ORDER BY id DESC
    ) AS position
    FROM support_ticket_context_snapshots
)
DELETE FROM support_ticket_context_snapshots snapshot
USING duplicate_context
WHERE snapshot.id = duplicate_context.id AND duplicate_context.position > 1;

CREATE UNIQUE INDEX uk_support_ticket_external_identity
    ON support_tickets(external_connection_id, external_id)
    WHERE external_connection_id IS NOT NULL AND external_id IS NOT NULL;
CREATE UNIQUE INDEX uk_support_context_snapshot_identity
    ON support_ticket_context_snapshots(ticket_id, context_type, source_reference, observed_at);

ALTER TABLE hr_candidate_consents
    ADD COLUMN purpose_code VARCHAR(40);
UPDATE hr_candidate_consents SET purpose_code = 'ASSESSMENT' WHERE purpose_code IS NULL;
ALTER TABLE hr_candidate_consents
    ALTER COLUMN purpose_code SET NOT NULL,
    ADD CONSTRAINT ck_hr_consent_purpose CHECK (purpose_code IN ('ASSESSMENT', 'ATS_IMPORT'));

CREATE TABLE hr_interview_session_members (
    session_id BIGINT NOT NULL REFERENCES hr_interview_sessions(id) ON DELETE CASCADE,
    actor_id VARCHAR(100) NOT NULL,
    member_role VARCHAR(32) NOT NULL DEFAULT 'INTERVIEWER',
    added_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, actor_id),
    CONSTRAINT ck_hr_interview_member_role CHECK (member_role IN ('OWNER', 'INTERVIEWER'))
);
INSERT INTO hr_interview_session_members(session_id, actor_id, member_role, added_by)
SELECT id, owner_actor_id, 'OWNER', owner_actor_id FROM hr_interview_sessions
ON CONFLICT DO NOTHING;

WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY question_key ORDER BY version DESC, updated_at DESC, id DESC
    ) AS position
    FROM hr_interview_question_bank
    WHERE active = TRUE
)
UPDATE hr_interview_question_bank question
SET active = FALSE, updated_at = now()
FROM ranked
WHERE question.id = ranked.id AND ranked.position > 1;
WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY checklist_key ORDER BY version DESC, updated_at DESC, id DESC
    ) AS position
    FROM hr_onboarding_checklists
    WHERE active = TRUE
)
UPDATE hr_onboarding_checklists checklist
SET active = FALSE, updated_at = now()
FROM ranked
WHERE checklist.id = ranked.id AND ranked.position > 1;
CREATE UNIQUE INDEX uk_hr_question_one_active
    ON hr_interview_question_bank(question_key) WHERE active = TRUE;
CREATE UNIQUE INDEX uk_hr_onboarding_checklist_one_active
    ON hr_onboarding_checklists(checklist_key) WHERE active = TRUE;

CREATE TABLE hr_onboarding_instances (
    id BIGSERIAL PRIMARY KEY,
    checklist_id BIGINT NOT NULL REFERENCES hr_onboarding_checklists(id),
    employee_reference VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    owner_actor_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    CONSTRAINT ck_hr_onboarding_instance_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'CANCELED')),
    CONSTRAINT uk_hr_onboarding_employee_checklist UNIQUE (employee_reference, checklist_id)
);

CREATE TABLE hr_onboarding_tasks (
    id BIGSERIAL PRIMARY KEY,
    instance_id BIGINT NOT NULL REFERENCES hr_onboarding_instances(id) ON DELETE CASCADE,
    item_key VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    guidance TEXT,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    owner_role VARCHAR(100),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    evidence_reference VARCHAR(1000),
    completed_by VARCHAR(100),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_hr_onboarding_task_status CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT uk_hr_onboarding_task_key UNIQUE (instance_id, item_key)
);
