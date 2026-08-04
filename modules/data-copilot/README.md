# data-copilot

English | [简体中文](#简体中文)

Natural-language database assistant with a reviewable, read-only SQL flow.

```mermaid
flowchart LR
    Question --> SchemaContext --> SQLCandidate --> Guardrails --> HumanConfirm --> GuardrailsAgain --> ReadOnlyQuery --> MaskedResult
```

Safety: schema-qualified table and fully-qualified column allowlists, wildcard rejection, `SELECT` only, aggregate-function allowlists, bounded literal `LIMIT`, database-stored SQL candidates, actor-bound one-time confirmation tokens, JDBC row/time/column/byte limits, sensitive-field masking, and audit metadata. Enterprise governance adds a bilingual metric dictionary, approved versioned query templates, datasource health and schema-drift checks, `EXPLAIN`-based row budgets, active JDBC cancellation, persisted masked results, controlled CSV/XLSX export, and one-time Report handoff. The platform database remains PostgreSQL + pgvector; the separate read-only business query target can be PostgreSQL or MySQL.

The 2.3 workbench exposes query, governance, records, and handoff tabs in both
`zh-CN` and `en-US`. SQL and guardrail evidence are shown before the visually
distinct high-risk confirmation; saving or executing keeps the user in the flow.

API: `POST /api/data-copilot/sql-candidates`, `POST /api/data-copilot/sql-candidates/{id}/execute`,
`GET /api/data-copilot/audit-logs`, `GET /api/data-copilot/query-results`,
`GET /api/data-copilot/report-handoffs`, `GET/POST /api/data-copilot/query-templates`,
`POST /api/data-copilot/query-templates/{id}/approve`,
`POST /api/data-copilot/query-templates/{id}/launch`, and
`POST /api/data-copilot/query-results/{id}/report-handoff`.

The Vue workbench now keeps the three enterprise stages connected: administrators
approve versioned templates; operators launch and confirm a template query; the
result snapshot and audit record remain available under execution records; and a
masked, actor-owned result can be handed to Report Copilot through a one-time
`READY` reference that becomes `CONSUMED` only after report generation succeeds.

Test: `./mvnw -pl modules/data-copilot -am test`

## 简体中文

自然语言数据库查询（Text to SQL）助手。SQL 必须使用 schema 完整表名和显式列白名单，`SELECT *`/`table.*` 会被拒绝；候选经过两次只读、函数白名单和有界 `LIMIT` 校验，并使用绑定操作者、对象、状态和有效期的一次性 token 确认。企业扩展包含业务指标词典、批准查询模板、数据源健康检查、schema 漂移提示、`EXPLAIN` 预估行数预算、运行中 JDBC 取消、脱敏结果留存、受控 CSV/XLSX 导出以及一次性 Report 交接。平台状态仍使用 PostgreSQL + pgvector，独立只读业务查询目标可使用 PostgreSQL 或 MySQL。

2.3 双语工作台覆盖查询、治理、记录和交接；SQL 与 Guardrail 证据在高风险确认前
展示，执行后留在当前流程并提示下一步。

API：`POST /api/data-copilot/sql-candidates`、`POST /api/data-copilot/sql-candidates/{id}/execute`、
`GET /api/data-copilot/audit-logs`、`GET /api/data-copilot/query-results`、
`GET /api/data-copilot/report-handoffs`、`GET/POST /api/data-copilot/query-templates`、
`POST /api/data-copilot/query-templates/{id}/approve`、
`POST /api/data-copilot/query-templates/{id}/launch` 和
`POST /api/data-copilot/query-results/{id}/report-handoff`。

Vue 工作台已把三个企业阶段串起来：管理员审批版本化模板；操作者启动并确认模板查询；执行记录同时保留结果快照和审计记录；脱敏且绑定操作者的结果可以通过一次性的 `READY` 引用交给 Report Copilot，报告生成成功后才变为 `CONSUMED`。
