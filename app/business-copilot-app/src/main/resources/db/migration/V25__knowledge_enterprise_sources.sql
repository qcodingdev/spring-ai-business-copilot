-- V25：Knowledge Copilot 企业资料来源、增量同步、源端删除和 ACL 快照。

CREATE TABLE knowledge_source_connections (
    id                  BIGSERIAL PRIMARY KEY,
    connection_key      VARCHAR(100) NOT NULL UNIQUE,
    display_name        VARCHAR(200) NOT NULL,
    provider            VARCHAR(40) NOT NULL,
    base_url            VARCHAR(500) NOT NULL,
    root_reference      VARCHAR(500),
    secret_ref          VARCHAR(200) NOT NULL,
    group_mapping       JSONB NOT NULL DEFAULT '{}'::jsonb,
    default_visibility  VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    owner_actor_id      VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_source_provider CHECK (provider IN
        ('SHAREPOINT', 'CONFLUENCE', 'NOTION', 'MOUNTED_DRIVE', 'S3', 'MINIO')),
    CONSTRAINT ck_knowledge_source_visibility CHECK (default_visibility IN
        ('ALL', 'HR_REVIEWER', 'ADMIN'))
);

CREATE TABLE knowledge_sync_runs (
    id                  BIGSERIAL PRIMARY KEY,
    connection_id       BIGINT NOT NULL REFERENCES knowledge_source_connections(id),
    status              VARCHAR(32) NOT NULL,
    cursor_before       TEXT,
    cursor_after        TEXT,
    fetched_count       INTEGER NOT NULL DEFAULT 0,
    created_count       INTEGER NOT NULL DEFAULT 0,
    updated_count       INTEGER NOT NULL DEFAULT 0,
    deleted_count       INTEGER NOT NULL DEFAULT 0,
    conflict_count      INTEGER NOT NULL DEFAULT 0,
    error_category      VARCHAR(100),
    requested_by        VARCHAR(100) NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    CONSTRAINT ck_knowledge_sync_status CHECK (status IN
        ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELED'))
);

CREATE TABLE knowledge_source_items (
    id                  BIGSERIAL PRIMARY KEY,
    connection_id       BIGINT NOT NULL REFERENCES knowledge_source_connections(id) ON DELETE CASCADE,
    source_item_id      VARCHAR(500) NOT NULL,
    source_version      VARCHAR(300),
    source_etag         VARCHAR(300),
    source_updated_at   TIMESTAMPTZ,
    content_hash        VARCHAR(64),
    acl_snapshot        JSONB NOT NULL DEFAULT '[]'::jsonb,
    visibility_scope    VARCHAR(32) NOT NULL,
    logical_document_id UUID,
    sync_status         VARCHAR(32) NOT NULL,
    deleted_at_source   TIMESTAMPTZ,
    last_synced_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_source_item UNIQUE (connection_id, source_item_id),
    CONSTRAINT ck_knowledge_source_item_status CHECK (sync_status IN
        ('CURRENT', 'UPDATED', 'DELETED', 'CONFLICT', 'FAILED')),
    CONSTRAINT ck_knowledge_source_item_visibility CHECK (visibility_scope IN
        ('ALL', 'HR_REVIEWER', 'ADMIN'))
);

CREATE INDEX idx_knowledge_source_items_connection_status
    ON knowledge_source_items(connection_id, sync_status, updated_at DESC);

ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS source_item_ref VARCHAR(700),
    ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS conflict_status VARCHAR(32) NOT NULL DEFAULT 'NONE';

ALTER TABLE knowledge_documents
    ADD CONSTRAINT ck_knowledge_document_conflict_status
        CHECK (conflict_status IN ('NONE', 'SOURCE_NEWER', 'LOCAL_NEWER', 'DIVERGED'));

CREATE INDEX idx_knowledge_documents_expiry_conflict
    ON knowledge_documents(expires_at, conflict_status)
    WHERE current_version = TRUE;
