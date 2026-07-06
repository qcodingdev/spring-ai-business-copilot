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
| 双重 guardrails | 生成时校验一次，执行前再校验一次（防御式） |
| 服务端确认 | 执行只使用服务端存储的 SQL，不信任客户端传回的 SQL |
| 候选 token | 通过 guardrails 的 SQL 才生成 confirmationToken；失败时不可执行 |
| 候选过期 | 默认 10 分钟过期，过期后不可执行 |
| 敏感字段 | password/token/secret/id_card 直接阻断查询；phone/email 结果脱敏 |
| 查询超时 | 默认 30 秒超时 |
| 结果截断 | 默认最多 100 行，超出标记 truncated |
| 审计日志 | 全生命周期记录，不记录完整结果或敏感原始值 |
| Schema 白名单 | query_audit_logs 不在白名单中，自然语言查询无法触达 |

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

- **无用户身份审计** — 当前不区分用户，审计只记录查询内容
- **无后台定时补偿** — 用户生成 SQL 后未执行，不会自动写审计；主动取消（token 无效/过期）会记录 NOT_CONFIRMED
- **内存候选存储** — 第一版用 ConcurrentHashMap，重启后候选丢失，不支持集群
- **AI 解释可能降级** — 模型调用失败时返回降级解释，不影响表格展示
- **只支持 PostgreSQL** — schema 元数据读取和 Flyway 迁移依赖 PostgreSQL
- **不做写操作** — 不支持 INSERT/UPDATE/DELETE
- **不做导出** — 不支持 CSV/PDF 等导出格式
- **不做图表** — 不支持可视化图表分析
- **不做登录/权限** — 无用户认证和角色控制
- **不做生产级优化** — 非生产级镜像、连接池、缓存等
