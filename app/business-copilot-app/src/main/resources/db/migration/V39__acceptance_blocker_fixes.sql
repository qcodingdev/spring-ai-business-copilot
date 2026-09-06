-- 修复升级验收发现的生产链路与并发约束问题。

-- SUP-01：追问属于工单分析状态，仓储始终从 support_tickets 读写。
ALTER TABLE support_tickets
    ADD COLUMN IF NOT EXISTS followup_questions JSONB;

UPDATE support_tickets ticket
SET followup_questions = latest_draft.followup_questions
FROM (
    SELECT DISTINCT ON (ticket_id) ticket_id, followup_questions
    FROM support_reply_drafts
    WHERE followup_questions IS NOT NULL
    ORDER BY ticket_id, created_at DESC, id DESC
) latest_draft
WHERE ticket.id = latest_draft.ticket_id
  AND ticket.followup_questions IS NULL;

ALTER TABLE support_reply_drafts
    DROP COLUMN IF EXISTS followup_questions;

-- DATA-04：同一修正链的序号必须唯一；并发修正由根候选行锁串行化。
WITH ordered AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY root_candidate_id
               ORDER BY created_at, id
           ) AS normalized_revision_index
    FROM data_candidate_revisions
)
UPDATE data_candidate_revisions revisions
SET revision_index = ordered.normalized_revision_index
FROM ordered
WHERE revisions.id = ordered.id
  AND revisions.revision_index <> ordered.normalized_revision_index;

CREATE UNIQUE INDEX IF NOT EXISTS uk_data_candidate_revisions_root_index
    ON data_candidate_revisions(root_candidate_id, revision_index);

-- DATA-01：候选必须持久化其采用的指标版本，执行前再次验证仍已审批且启用。
CREATE TABLE IF NOT EXISTS data_candidate_metric_refs (
    candidate_id   VARCHAR(64) NOT NULL
        REFERENCES data_sql_candidates(candidate_id) ON DELETE CASCADE,
    metric_key     VARCHAR(100) NOT NULL,
    metric_version BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (candidate_id, metric_key),
    CONSTRAINT ck_data_candidate_metric_version CHECK (metric_version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_data_candidate_metric_refs_metric
    ON data_candidate_metric_refs(metric_key, metric_version);

-- DATA-05：追溯记录必须引用真实业务对象，不能写入悬空对象标识。
ALTER TABLE report_draft_data_links
    ADD CONSTRAINT fk_report_trace_draft
        FOREIGN KEY (draft_id) REFERENCES report_drafts(id) ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT fk_report_trace_result
        FOREIGN KEY (query_result_id) REFERENCES data_query_results(id) ON DELETE SET NULL NOT VALID,
    ADD CONSTRAINT fk_report_trace_candidate
        FOREIGN KEY (candidate_id) REFERENCES data_sql_candidates(candidate_id) ON DELETE SET NULL NOT VALID,
    ADD CONSTRAINT fk_report_trace_handoff
        FOREIGN KEY (handoff_id) REFERENCES data_report_handoffs(id) ON DELETE SET NULL NOT VALID;

-- SUP-05：质量案例只能关联真实草稿，并保留真实工单关系。
ALTER TABLE support_quality_cases
    ADD COLUMN IF NOT EXISTS ticket_id BIGINT;

UPDATE support_quality_cases quality_case
SET ticket_id = draft.ticket_id
FROM support_reply_drafts draft
WHERE quality_case.draft_id = draft.id
  AND quality_case.ticket_id IS NULL;

ALTER TABLE support_quality_cases
    ADD CONSTRAINT fk_support_quality_ticket
        FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT fk_support_quality_draft
        FOREIGN KEY (draft_id) REFERENCES support_reply_drafts(id) ON DELETE CASCADE NOT VALID;

CREATE INDEX IF NOT EXISTS idx_support_quality_cases_ticket
    ON support_quality_cases(ticket_id, created_at DESC);

-- RUN-02：每次真实供应商调用先持久化预算预留，再更新实际结果。
ALTER TABLE task_attempts DROP CONSTRAINT IF EXISTS ck_task_attempts_outcome;
ALTER TABLE task_attempts
    ADD CONSTRAINT ck_task_attempts_outcome
        CHECK (outcome IN ('STARTED', 'SUCCESS', 'FAILURE', 'UNKNOWN'));

CREATE INDEX IF NOT EXISTS idx_task_attempts_started
    ON task_attempts(occurred_at, run_id)
    WHERE outcome = 'STARTED';

-- HR-01：授权撤回立即停止派生流程，并为授权与 ATS 派生数据声明保留截止时间。
ALTER TABLE hr_candidate_consents
    ADD COLUMN IF NOT EXISTS retention_expires_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION set_hr_consent_retention_expiry()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.retention_expires_at IS NULL
       OR (TG_OP = 'UPDATE' AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
           AND NEW.revoked_at IS NOT NULL) THEN
        NEW.retention_expires_at := COALESCE(NEW.revoked_at, NEW.expires_at) + INTERVAL '30 days';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hr_consent_retention_expiry ON hr_candidate_consents;
CREATE TRIGGER trg_hr_consent_retention_expiry
    BEFORE INSERT OR UPDATE OF revoked_at, expires_at, retention_expires_at
    ON hr_candidate_consents
    FOR EACH ROW EXECUTE FUNCTION set_hr_consent_retention_expiry();

UPDATE hr_candidate_consents
SET retention_expires_at = COALESCE(revoked_at, expires_at) + INTERVAL '30 days'
WHERE retention_expires_at IS NULL;
ALTER TABLE hr_candidate_consents ALTER COLUMN retention_expires_at SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_hr_candidate_consents_retention
    ON hr_candidate_consents(retention_expires_at);

ALTER TABLE hr_ats_imports
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION set_hr_ats_import_expiry()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.expires_at IS NULL THEN
        NEW.expires_at := NEW.imported_at + INTERVAL '30 days';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hr_ats_import_expiry ON hr_ats_imports;
CREATE TRIGGER trg_hr_ats_import_expiry
    BEFORE INSERT OR UPDATE OF imported_at, expires_at
    ON hr_ats_imports
    FOR EACH ROW EXECUTE FUNCTION set_hr_ats_import_expiry();

UPDATE hr_ats_imports
SET expires_at = imported_at + INTERVAL '30 days'
WHERE expires_at IS NULL;
ALTER TABLE hr_ats_imports ALTER COLUMN expires_at SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_hr_ats_imports_expiry ON hr_ats_imports(expires_at);
