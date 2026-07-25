-- V19：知识资料可见范围、系统托管标记和受控检索审计。
-- 历史文档默认全员可见；公网初始化服务会为 HR 制度写入更严格的可见范围。

ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS visibility_scope VARCHAR(32),
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE knowledge_documents
SET visibility_scope = COALESCE(visibility_scope, 'ALL');

ALTER TABLE knowledge_documents
    ALTER COLUMN visibility_scope SET NOT NULL;

ALTER TABLE knowledge_documents
    DROP CONSTRAINT IF EXISTS ck_knowledge_document_visibility;
ALTER TABLE knowledge_documents
    ADD CONSTRAINT ck_knowledge_document_visibility
        CHECK (visibility_scope IN ('ALL', 'HR_REVIEWER', 'ADMIN'));

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_retrieval_scope
    ON knowledge_documents(category, visibility_scope, enabled, current_version, index_status);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_system_managed
    ON knowledge_documents(system_managed, logical_document_id, current_version);

ALTER TABLE knowledge_qa_audit_logs
    ADD COLUMN IF NOT EXISTS category_filter VARCHAR(100),
    ADD COLUMN IF NOT EXISTS access_scope VARCHAR(32);
