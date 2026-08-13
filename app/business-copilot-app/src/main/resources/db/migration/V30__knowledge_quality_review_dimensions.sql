-- V30：将知识质量人工复核从单一结论扩展为可审计的证据、答案和后续动作判断。

ALTER TABLE knowledge_qa_audit_logs
    ADD COLUMN IF NOT EXISTS answer_preview VARCHAR(2000);

ALTER TABLE knowledge_quality_reviews
    ADD COLUMN IF NOT EXISTS evidence_assessment VARCHAR(40) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN IF NOT EXISTS answer_assessment VARCHAR(40) NOT NULL DEFAULT 'NOT_VERIFIABLE',
    ADD COLUMN IF NOT EXISTS remediation_action VARCHAR(40) NOT NULL DEFAULT 'NONE';

ALTER TABLE knowledge_quality_reviews
    ADD CONSTRAINT ck_knowledge_quality_reviews_evidence_assessment
        CHECK (evidence_assessment IN ('SUFFICIENT', 'INSUFFICIENT', 'CONFLICTING', 'OUTDATED', 'NOT_APPLICABLE')),
    ADD CONSTRAINT ck_knowledge_quality_reviews_answer_assessment
        CHECK (answer_assessment IN ('ACCURATE', 'PARTIALLY_ACCURATE', 'INACCURATE', 'NOT_VERIFIABLE')),
    ADD CONSTRAINT ck_knowledge_quality_reviews_remediation_action
        CHECK (remediation_action IN ('NONE', 'REINDEX_SOURCE', 'UPDATE_KNOWLEDGE', 'ADJUST_POLICY', 'FOLLOW_UP_WITH_REQUESTER'));
