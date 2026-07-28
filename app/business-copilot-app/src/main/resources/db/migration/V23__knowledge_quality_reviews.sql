-- V23：知识质量问题人工处置。
-- 每个问答保留最近一次处置；单调递增的 revision 用于确定性并发判断，时间只用于展示。

ALTER TABLE knowledge_answer_feedback
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1;

ALTER TABLE knowledge_answer_feedback
    ADD CONSTRAINT ck_knowledge_answer_feedback_revision
        CHECK (revision >= 1);

CREATE TABLE IF NOT EXISTS knowledge_quality_reviews (
    id                  BIGSERIAL PRIMARY KEY,
    audit_log_id        BIGINT        NOT NULL
        REFERENCES knowledge_qa_audit_logs(id) ON DELETE CASCADE,
    decision            VARCHAR(40)   NOT NULL,
    review_note         VARCHAR(1000) NOT NULL,
    reviewer_actor_id   VARCHAR(100)  NOT NULL,
    reviewed_issue_version BIGINT     NOT NULL,
    reviewed_issue_at   TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_quality_reviews_audit UNIQUE (audit_log_id),
    CONSTRAINT ck_knowledge_quality_reviews_decision
        CHECK (decision IN ('RESOLVED', 'DISMISSED', 'KNOWLEDGE_UPDATE_REQUIRED')),
    CONSTRAINT ck_knowledge_quality_reviews_note
        CHECK (length(btrim(review_note)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_knowledge_quality_reviews_issue_version
        CHECK (reviewed_issue_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_quality_reviews_decision
    ON knowledge_quality_reviews(decision, updated_at DESC);
