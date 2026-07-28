-- V22：知识问答反馈和质量复核队列。
-- 反馈只能绑定已持久化的问答审计记录；同一操作者可幂等更新自己的反馈。

CREATE TABLE IF NOT EXISTS knowledge_answer_feedback (
    id              BIGSERIAL PRIMARY KEY,
    audit_log_id    BIGINT       NOT NULL
        REFERENCES knowledge_qa_audit_logs(id) ON DELETE CASCADE,
    actor_id        VARCHAR(100) NOT NULL,
    rating          VARCHAR(20)  NOT NULL,
    reason          VARCHAR(40),
    comment         VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_answer_feedback_actor
        UNIQUE (audit_log_id, actor_id),
    CONSTRAINT ck_knowledge_answer_feedback_rating
        CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL')),
    CONSTRAINT ck_knowledge_answer_feedback_reason
        CHECK (reason IS NULL OR reason IN (
            'MISSING_EVIDENCE',
            'INCORRECT',
            'OUTDATED',
            'UNCLEAR',
            'OTHER'
        )),
    CONSTRAINT ck_knowledge_answer_feedback_negative_reason
        CHECK (
            (rating = 'HELPFUL' AND reason IS NULL)
            OR (rating = 'NOT_HELPFUL' AND reason IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_knowledge_answer_feedback_quality
    ON knowledge_answer_feedback(rating, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_answer_feedback_audit
    ON knowledge_answer_feedback(audit_log_id);
