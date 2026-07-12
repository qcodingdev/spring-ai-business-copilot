# data-copilot

English | [简体中文](#简体中文)

Natural-language database assistant with a reviewable, read-only SQL flow.

```mermaid
flowchart LR
    Question --> SchemaContext --> SQLCandidate --> Guardrails --> HumanConfirm --> GuardrailsAgain --> ReadOnlyQuery --> MaskedResult
```

Safety: allowlisted tables, `SELECT` only, server-stored SQL, one-time confirmation token, row/time limits, sensitive-field masking, and audit metadata.

API: `POST /api/data-copilot/sql-candidates`, `POST /api/data-copilot/sql-candidates/{id}/execute`, `GET /api/data-copilot/audit-logs`.

Test: `./mvnw -pl modules/data-copilot -am test`

## 简体中文

自然语言数据库查询助手。SQL 必须经过两次只读校验并由用户确认，只执行服务端保存的候选 SQL，结果脱敏且全流程审计。
