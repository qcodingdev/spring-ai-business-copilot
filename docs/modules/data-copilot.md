# data-copilot

## 业务价值

将自然语言业务问题转换为可确认、只读、可审计的 SQL 查询。

## 核心流程

```mermaid
flowchart LR
    Q["Question"] --> SCHEMA["Schema Context"] --> AI["SQL Candidate"]
    AI --> G1["Guardrails"] --> CONFIRM["Human Confirm"]
    CONFIRM --> G2["Guardrails Again"] --> EXEC["Read-only Execute"]
    EXEC --> MASK["Mask Result"] --> EXPLAIN["AI Explanation"]
```

## 安全边界

- 仅允许白名单 `schema.table`、白名单 `schema.table.column` 和 `SELECT` / `WITH ... SELECT`；拒绝 `SELECT *`/`table.*`。
- SQL candidate 保存于平台数据库，客户端只提交创建响应中返回一次且绑定操作者的确认 token。
- 查询限制行数、超时并记录审计。

## v1.2 升级范围

- candidate 已从内存 Map 迁移到 `data_sql_candidates`，保存 owner、token digest、状态和有效期。
- 执行已使用条件消费，覆盖跨 actor、过期、重放和并发确认。
- 已建立窄 `BusinessDatabaseDialect`，统一 PostgreSQL/MySQL URL、驱动、只读 session 和 metadata 差异。
- PostgreSQL/MySQL 使用同一 schema/table/column、通配符、函数、LIMIT、敏感列和资源上限契约。
- 外部业务库始终使用独立最小权限只读账号，平台 Flyway 和审计不得使用查询 DataSource。

## API

- `POST /api/data-copilot/sql-candidates`
- `POST /api/data-copilot/sql-candidates/{id}/execute`
- `GET /api/data-copilot/audit-logs`

## 验证

`./mvnw -pl modules/data-copilot -am test`
