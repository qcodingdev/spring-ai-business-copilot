# Data Copilot 模块文档

## 业务价值

Data Copilot 让非技术用户用自然语言查询业务数据库，获取安全、可解释的结果。

典型场景：

- 运营人员查看销售趋势和客户分布
- 管理层快速获取 KPI 数据
- 产品经理分析退款率和用户行为
- 替代手动编写 SQL 或等待数据团队排期

核心价值：**降低数据查询门槛，同时保证安全底线。**

---

## 核心流程

```
用户提问
  ↓
Schema 上下文 + Prompt → LLM 生成 SQL
  ↓
Guardrails 校验（第一道）
  ↓  通过               ↓  失败
保存候选                返回违规原因，不可执行
返回 candidateId + token
  ↓
用户确认
  ↓
取出服务端 SQL（不信任客户端）
  ↓
Guardrails 校验（第二道，防御式）
  ↓  通过               ↓  失败
执行只读 SQL             拒绝，写审计
  ↓
脱敏 + 截断
  ↓
AI 解释（失败时降级）
  ↓
返回 table + explanation + 审计
```

---

## API 列表

Base path: `/api/data-copilot`

### GET /schema

返回白名单表的 schema 摘要。

**Response:**

```json
{
  "success": true,
  "data": {
    "tables": [
      { "name": "customers", "columns": [...], "description": "客户表" }
    ],
    "textSummary": "Table: customers ..."
  }
}
```

### POST /sql-candidates

生成 SQL 候选。

**Request:**

```json
{ "question": "上个月总销售额是多少？" }
```

**Response（guardrails 通过）:**

```json
{
  "success": true,
  "data": {
    "requestId": "req-001",
    "question": "上个月总销售额是多少？",
    "sql": "SELECT SUM(amount) FROM orders ...",
    "summary": "上月总销售额",
    "assumptions": [],
    "warnings": [],
    "validation": { "passed": true, "violations": [] },
    "executable": true,
    "candidateId": "cand-1",
    "confirmationToken": "token-1",
    "expiresAt": "2026-07-06T10:00:00Z"
  }
}
```

**Response（guardrails 失败）:**

```json
{
  "success": true,
  "data": {
    "requestId": "req-002",
    "sql": "DELETE FROM customers",
    "validation": { "passed": false, "violations": ["FORBIDDEN_KEYWORD: delete"] },
    "executable": false,
    "candidateId": null,
    "confirmationToken": null,
    "expiresAt": null
  }
}
```

### POST /sql-candidates/{candidateId}/execute

执行已确认的 SQL 候选。请求体**只允许** `confirmationToken`，不允许传 SQL。

**Request:**

```json
{ "confirmationToken": "token-1" }
```

**Response:**

```json
{
  "success": true,
  "data": {
    "table": {
      "columns": [{ "name": "id", "type": "integer" }],
      "rows": [{ "values": { "id": 1 } }],
      "rowCount": 1,
      "truncated": false
    },
    "explanation": {
      "explanation": "上月总销售额为 10000 元。",
      "degraded": false
    }
  }
}
```

### GET /audit-logs?page=0&size=20

返回最近审计日志，分页。

**Response:**

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "requestId": "req-001",
      "userQuestion": "上个月总销售额",
      "executionStatus": "EXECUTED",
      "rowCount": 1,
      "createdAt": "2026-07-06T10:00:00Z"
    }
  ]
}
```

---

## 安全边界

| 安全机制 | 说明 |
|---|---|
| 只读 SQL | 只允许 SELECT / WITH SELECT；INSERT/UPDATE/DELETE/DROP 等全部拦截 |
| 表与列白名单 | 表必须使用 `schema.table`，列必须位于 `schema.table.column` 白名单；拒绝 `SELECT *` 和 `table.*` |
| 双重 guardrails | 生成时校验一次，执行前再校验一次（防御式） |
| 服务端确认 | 执行只使用服务端存储的 SQL，不信任客户端传回的 SQL |
| 候选 token | 只保存摘要并绑定 owner、对象、状态和有效期；跨 actor、重放和并发消费失败关闭 |
| 候选过期 | 默认 10 分钟过期，过期后不可执行 |
| 敏感字段 | password/token/secret/id_card 直接阻断查询；phone/email 结果脱敏 |
| 查询超时 | 默认 30 秒超时 |
| 结果截断 | 默认最多 100 行，超出标记 truncated |
| 审计日志 | 全生命周期记录，不记录完整结果或敏感原始值 |
| Schema 白名单 | query_audit_logs 不在白名单中，自然语言查询无法触达 |

接入自定义 PostgreSQL/MySQL 业务库时，部署方必须同时配置
`business-copilot.data-copilot.schema.queryable-tables` 和
`business-copilot.guardrails.queryable-columns`。未配置目标库列白名单时，
守卫不会信任动态 metadata 自动放开列。

---

## 示例问题

以下问题可在工作台上直接输入（需配置 AI 模型）：

| 问题 | 对应 SQL 类型 |
|---|---|
| 上个月总销售额是多少？ | `SELECT SUM(total_amount) FROM orders` |
| 哪些商品退款率最高？ | `SELECT ... FROM refunds JOIN products` |
| 本周新增用户有多少？ | `SELECT COUNT(*) FROM customers WHERE created_at > ...` |
| 哪类客户的客单价最高？ | `SELECT ... FROM customers JOIN orders GROUP BY ...` |
| 618 大促的预算是多少？ | `SELECT budget FROM marketing_events WHERE name = '618 大促'` |

示例数据包含 5 个客户、8 个商品、7 个订单、1 条退款记录、3 个营销活动。

---

## 已知限制

- **身份由宿主提供** — 模块通过 `CurrentActorProvider` 读取操作者；非 HTTP 宿主必须显式提供受控实现
- **未执行候选不会自动执行** — 候选过期、重放或状态竞争都会失败关闭
- **数据库候选存储** — 候选保存 owner、状态、token 摘要和过期时间，使用条件更新防止重放和并发确认
- **AI 解释可能降级** — 模型调用失败时返回降级解释，不影响表格展示
- **平台与查询目标分离** — 平台仍使用 PostgreSQL + pgvector，外部业务查询目标支持 PostgreSQL/MySQL
- **不做写操作** — 不支持 INSERT/UPDATE/DELETE
- **不做导出** — 不支持 CSV/PDF 等导出格式
- **不做图表** — 不支持可视化图表分析
- **不提供 IAM 管理** — 登录、用户目录和角色管理由宿主负责，模块只消费 actor/role/object policy
- **不做生产级优化** — 非生产级镜像、连接池、缓存等

---

## 框架迁移边界

- Spring AI 保持 2.0.0，SQL 候选改造为 `ChatClient.entity(...)` + schema validation 后，仍必须进入现有 SQL Guardrails。
- `JdbcReadOnlyQueryExecutor` 必须保留 JDBC，因为结果列和 SQL 都在运行时确定。
- `JdbcSchemaMetadataRepository` 必须保留 JDBC，因为它读取 `information_schema`，不是实体 CRUD。
- `query_audit_logs` 继续由显式 JDBC Repository 管理，强执行 intent 与候选消费共享平台事务。
- 审计写入失败策略需要改为风险驱动；已确认 SQL 执行的关键审计不能静默丢失。
- 候选已落平台数据库，当前不引入 Redis。
