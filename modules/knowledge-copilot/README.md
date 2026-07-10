# knowledge-copilot

English | [简体中文](#简体中文)

## English

Enterprise knowledge assistant for Markdown/TXT ingestion, pgvector retrieval, cited answers, and no-evidence refusal.

### Flow

1. Validate and sanitize a document.
2. Parse and chunk it while preserving source metadata.
3. Generate embeddings through Spring AI.
4. Store metadata/chunks and pgvector embeddings.
5. Retrieve enabled chunks and generate a cited answer.
6. Reject unsupported citations or return `NO_EVIDENCE`.

### Persistence Plan

- Migrate document, chunk, and QA audit CRUD to MyBatis-Plus.
- Keep `JdbcKnowledgeEmbeddingRepository` for vector binding and distance search.
- Preserve Repository interfaces and business transactions.

### Known Limits

- Markdown and TXT only.
- Embedding dimensions must match the database vector column.
- No document-level authorization or multi-tenancy.

### Test

```bash
../../mvnw -f ../../pom.xml -pl modules/knowledge-copilot -am test
```

## 简体中文

Knowledge Copilot 实现 Markdown/TXT 文档解析、分片、embedding、pgvector 检索、有引用回答和无依据拒答。

文档、分片和问答审计 CRUD 计划迁移到 MyBatis-Plus；vector 写入与距离检索继续使用 JDBC。两种持久层共用 Spring 事务。
