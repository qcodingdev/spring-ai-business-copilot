# data-copilot

English | [简体中文](#简体中文)

Natural-language database assistant with a reviewable, read-only SQL flow.

```mermaid
flowchart LR
    Question --> SchemaContext --> SQLCandidate --> Guardrails --> HumanConfirm --> GuardrailsAgain --> ReadOnlyQuery --> MaskedResult
```

Safety: schema-qualified table and fully-qualified column allowlists, wildcard rejection, `SELECT` only, aggregate-function allowlists, bounded literal `LIMIT`, database-stored SQL candidates, actor-bound one-time confirmation tokens, JDBC row/time/column/byte limits, sensitive-field masking, and audit metadata. The platform database remains PostgreSQL + pgvector; the separate read-only business query target can be PostgreSQL or MySQL.

API: `POST /api/data-copilot/sql-candidates`, `POST /api/data-copilot/sql-candidates/{id}/execute`, `GET /api/data-copilot/audit-logs`.

Test: `./mvnw -pl modules/data-copilot -am test`

## 简体中文

自然语言数据库查询（Text to SQL）助手。SQL 必须使用 schema 完整表名和显式列白名单，`SELECT *`/`table.*` 会被拒绝；候选经过两次只读、函数白名单和有界 `LIMIT` 校验，并使用绑定操作者、对象、状态和有效期的一次性 token 确认。执行层独立限制 timeout、行数、列数和结果字节数，结果脱敏且全流程审计。平台状态仍使用 PostgreSQL + pgvector，独立只读业务查询目标可使用 PostgreSQL 或 MySQL。
