-- V14: Support Copilot 2.0 enum boundaries, complete state machines,
-- knowledge-version binding, and human edit feedback.

UPDATE support_tickets SET status = 'CLASSIFIED' WHERE status = 'DRAFTED';
UPDATE support_tickets SET category = 'OTHER'
WHERE category IS NULL OR category NOT IN (
    'REFUND', 'ACCOUNT_ACTIVATION', 'INCIDENT', 'ACCOUNT_SECURITY',
    'BILLING', 'PRODUCT_USAGE', 'OTHER'
);
UPDATE support_tickets SET sentiment = 'NEUTRAL'
WHERE sentiment IS NULL OR sentiment NOT IN ('NEUTRAL', 'CONFUSED', 'FRUSTRATED', 'ANGRY');
UPDATE support_tickets SET urgency = 'MEDIUM'
WHERE urgency IS NULL OR urgency NOT IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');

ALTER TABLE support_tickets
    ALTER COLUMN category SET NOT NULL,
    ALTER COLUMN sentiment SET NOT NULL,
    ALTER COLUMN urgency SET NOT NULL;

ALTER TABLE support_tickets DROP CONSTRAINT IF EXISTS ck_support_ticket_category;
ALTER TABLE support_tickets ADD CONSTRAINT ck_support_ticket_category CHECK (
    category IN ('REFUND', 'ACCOUNT_ACTIVATION', 'INCIDENT', 'ACCOUNT_SECURITY',
                 'BILLING', 'PRODUCT_USAGE', 'OTHER')
);
ALTER TABLE support_tickets DROP CONSTRAINT IF EXISTS ck_support_ticket_sentiment;
ALTER TABLE support_tickets ADD CONSTRAINT ck_support_ticket_sentiment CHECK (
    sentiment IN ('NEUTRAL', 'CONFUSED', 'FRUSTRATED', 'ANGRY')
);
ALTER TABLE support_tickets DROP CONSTRAINT IF EXISTS ck_support_ticket_urgency;
ALTER TABLE support_tickets ADD CONSTRAINT ck_support_ticket_urgency CHECK (
    urgency IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
);
ALTER TABLE support_tickets DROP CONSTRAINT IF EXISTS ck_support_ticket_status;
ALTER TABLE support_tickets ADD CONSTRAINT ck_support_ticket_status CHECK (
    status IN ('RECEIVED', 'CLASSIFIED', 'DRAFTED', 'NEEDS_HUMAN',
               'CONFIRMED', 'CANCELED', 'CLOSED', 'FAILED')
);

ALTER TABLE support_reply_drafts
    ADD COLUMN IF NOT EXISTS knowledge_version_ids TEXT,
    ADD COLUMN IF NOT EXISTS original_draft_text TEXT,
    ADD COLUMN IF NOT EXISTS edited_draft_text TEXT,
    ADD COLUMN IF NOT EXISTS edit_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS edited_by_actor_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decision_outcome VARCHAR(40);

UPDATE support_reply_drafts
SET original_draft_text = COALESCE(original_draft_text, draft_text),
    decision_outcome = COALESCE(decision_outcome, CASE status
        WHEN 'CONFIRMED' THEN 'ACCEPTED'
        WHEN 'CANCELED' THEN 'REJECTED'
        ELSE 'PENDING'
    END);

ALTER TABLE support_reply_drafts
    ALTER COLUMN original_draft_text SET NOT NULL,
    ALTER COLUMN decision_outcome SET NOT NULL;

ALTER TABLE support_reply_drafts DROP CONSTRAINT IF EXISTS ck_support_draft_status;
ALTER TABLE support_reply_drafts ADD CONSTRAINT ck_support_draft_status CHECK (
    status IN ('DRAFTED', 'NEEDS_REVIEW', 'CONFIRMED', 'CANCELED', 'EXPIRED')
);
ALTER TABLE support_reply_drafts DROP CONSTRAINT IF EXISTS ck_support_draft_risk;
ALTER TABLE support_reply_drafts ADD CONSTRAINT ck_support_draft_risk CHECK (
    risk_level IN ('LOW', 'MEDIUM', 'HIGH')
);
ALTER TABLE support_reply_drafts DROP CONSTRAINT IF EXISTS ck_support_draft_outcome;
ALTER TABLE support_reply_drafts ADD CONSTRAINT ck_support_draft_outcome CHECK (
    decision_outcome IN ('PENDING', 'ACCEPTED', 'EDITED_ACCEPTED', 'REJECTED')
);
