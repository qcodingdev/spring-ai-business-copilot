-- V13: Knowledge Copilot 2.0 document versions, asynchronous indexing jobs,
-- ownership, and PostgreSQL full-text retrieval.

ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS logical_document_id UUID,
    ADD COLUMN IF NOT EXISTS version_no INTEGER,
    ADD COLUMN IF NOT EXISTS current_version BOOLEAN,
    ADD COLUMN IF NOT EXISTS index_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS index_error_category VARCHAR(80),
    ADD COLUMN IF NOT EXISTS content_type VARCHAR(160),
    ADD COLUMN IF NOT EXISTS owner_actor_id VARCHAR(100);

UPDATE knowledge_documents
SET logical_document_id = COALESCE(logical_document_id, gen_random_uuid()),
    version_no = COALESCE(version_no, 1),
    current_version = COALESCE(current_version, TRUE),
    index_status = COALESCE(index_status, CASE WHEN enabled THEN 'INDEXED' ELSE 'PENDING' END),
    content_type = COALESCE(content_type, 'text/plain'),
    owner_actor_id = COALESCE(owner_actor_id, 'system');

ALTER TABLE knowledge_documents
    ALTER COLUMN logical_document_id SET NOT NULL,
    ALTER COLUMN version_no SET NOT NULL,
    ALTER COLUMN current_version SET NOT NULL,
    ALTER COLUMN index_status SET NOT NULL,
    ALTER COLUMN owner_actor_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_knowledge_documents_logical_version
    ON knowledge_documents(logical_document_id, version_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_knowledge_documents_one_current
    ON knowledge_documents(logical_document_id) WHERE current_version = TRUE;
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_owner_current
    ON knowledge_documents(owner_actor_id, current_version, updated_at DESC);

ALTER TABLE knowledge_documents DROP CONSTRAINT IF EXISTS ck_knowledge_document_index_status;
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_document_index_status
    CHECK (index_status IN ('PENDING', 'PROCESSING', 'INDEXED', 'RETRYABLE', 'FAILED', 'DISABLED'));

CREATE TABLE IF NOT EXISTS knowledge_index_jobs (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    status          VARCHAR(32) NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    embedding_model VARCHAR(200),
    embedding_dim   INTEGER,
    chunk_count     INTEGER,
    error_category  VARCHAR(80),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_index_jobs_dispatch
    ON knowledge_index_jobs(status, next_attempt_at, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_knowledge_index_jobs_active_document
    ON knowledge_index_jobs(document_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRYABLE');

ALTER TABLE knowledge_index_jobs DROP CONSTRAINT IF EXISTS ck_knowledge_index_job_status;
ALTER TABLE knowledge_index_jobs ADD CONSTRAINT ck_knowledge_index_job_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'RETRYABLE', 'COMPLETED', 'FAILED', 'CANCELED'));

CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_full_text
    ON knowledge_chunks USING GIN (to_tsvector('simple', content));
