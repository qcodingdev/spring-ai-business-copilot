# data-copilot

English | [简体中文](#简体中文)

Natural-language database assistant with a reviewable, read-only SQL flow.

```mermaid
flowchart LR
    Question --> SchemaContext --> SQLCandidate --> Guardrails --> HumanConfirm --> GuardrailsAgain --> ReadOnlyQuery --> MaskedResult
```

Safety: schema-qualified table allowlists, `SELECT` only, aggregate-function allowlists, bounded literal `LIMIT`, server-stored SQL, one-time confirmation token, JDBC row/time/column/byte limits, sensitive-field masking, and audit metadata.

API: `POST /api/data-copilot/sql-candidates`, `POST /api/data-copilot/sql-candidates/{id}/execute`, `GET /api/data-copilot/audit-logs`.

Test: `./mvnw -pl modules/data-copilot -am test`

## 简体中文

自然语言数据库查询（Text to SQL）助手。SQL 必须使用 schema 完整表名，经过两次只读、函数白名单和有界 `LIMIT` 校验并由用户确认；执行层独立限制 timeout、行数、列数和结果字节数，结果脱敏且全流程审计。
