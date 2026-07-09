-- V4: 创建 Knowledge Copilot 相关表。
-- 启用 pgvector 扩展，创建知识文档、分片、向量嵌入和问答审计日志表。
-- 审计日志只记录元信息，不记录完整原始文档内容或敏感字段明文值。

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ── 知识文档表 ──────────────────────────────────────────────
-- 记录上传文档的元数据，包括标题、来源、分类、内容哈希和启用状态。
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(500)  NOT NULL,
    source_type     VARCHAR(50)   NOT NULL DEFAULT 'upload',
    source_name     VARCHAR(500),
    category        VARCHAR(100),
    content_hash    VARCHAR(64)   NOT NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_enabled ON knowledge_documents(enabled);
CREATE UNIQUE INDEX IF NOT EXISTS idx_knowledge_documents_content_hash ON knowledge_documents(content_hash);

-- ── 知识分片表 ──────────────────────────────────────────────
-- 记录文档解析后的分片。content 存放脱敏后的文本，content_preview 存放短摘要。
-- token_count 为估算值，方便后续截断和成本评估。
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT        NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    section_title   VARCHAR(500),
    chunk_index     INTEGER       NOT NULL,
    content         TEXT          NOT NULL,
    content_preview VARCHAR(500),
    token_count     INTEGER,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_document_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_chunk_index ON knowledge_chunks(document_id, chunk_index);

-- ── 知识分片向量嵌入表 ─────────────────────────────────────
-- 存储 chunk 的 embedding 向量。
-- embedding 维度（1536）必须与 business-copilot.knowledge.embedding-dimension 配置
-- 以及实际 embedding 模型输出维度一致。若维度不匹配，写入或检索将报错。
-- 默认 1536 对应 text-embedding-3-small；若使用其他模型需重建此列。
CREATE TABLE IF NOT EXISTS knowledge_chunk_embeddings (
    id              BIGSERIAL PRIMARY KEY,
    chunk_id        BIGINT        NOT NULL REFERENCES knowledge_chunks(id) ON DELETE CASCADE,
    embedding_model VARCHAR(200)  NOT NULL,
    embedding       vector(1536)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_knowledge_chunk_embeddings_chunk_id ON knowledge_chunk_embeddings(chunk_id);

-- ── 知识问答审计日志表 ─────────────────────────────────────
-- 记录知识问答全流程审计信息。不记录完整原始文档内容，不记录完整敏感字段值。
CREATE TABLE IF NOT EXISTS knowledge_qa_audit_logs (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          VARCHAR(64),
    question            TEXT,
    retrieved_chunk_ids TEXT,
    cited_chunk_ids     TEXT,
    answer_status       VARCHAR(50),
    refusal_reason      TEXT,
    model_name          VARCHAR(100),
    embedding_model     VARCHAR(200),
    latency_ms          BIGINT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_qa_audit_logs_created_at ON knowledge_qa_audit_logs(created_at DESC);
