# 第一版范围与实现规格

## 1. 文档目的

本文档用于把第一版 Data Copilot 的产品目标、功能拆解、系统边界、模块边界、接口设计和验收标准整理成可执行规格。

它可以直接交给 AI 生成项目代码，但生成代码时必须继续遵守仓库根目录 `AGENTS.md` 的约束。

统一包名要求：

- Maven `groupId` 使用 `dev.qcoding`。
- Java 包名必须以 `dev.qcoding.businesscopilot` 开头。
- 各模块建议包名前缀：
  - `dev.qcoding.businesscopilot.app`
  - `dev.qcoding.businesscopilot.datacopilot`
  - `dev.qcoding.businesscopilot.aicore`
  - `dev.qcoding.businesscopilot.guardrails`
  - `dev.qcoding.businesscopilot.audit`
  - `dev.qcoding.businesscopilot.commonweb`

## 2. 当前项目文档分析结论

当前项目已经明确：

- 项目定位：Spring AI Business Copilot 是 Java AI 业务智能助手套件，不是 AI 框架。
- 第一版范围：只实现 Data Copilot。
- 业务价值：让业务人员用自然语言安全查询业务数据库，并获得 SQL、表格结果和业务解释。
- 架构方向：使用 Spring Boot、Spring AI、prompt 模板、guardrails、审计和示例业务数据。
- 安全红线：Data Copilot 默认只读，SQL 执行前必须确认，查询必须审计，敏感字段必须脱敏。
- 扩展要求：架构要预留 Resume Copilot、Support Copilot、Knowledge Copilot、Report Copilot，但不能提前过度抽象。

## 3. 第一版产品目标

构建一个可直接运行的 Data Copilot：

- 面向个人开发者、中小团队和企业内部系统。
- 提供自然语言到 SQL 的业务查询体验。
- 提供 SQL 安全校验、执行前确认、结果表格、AI 解释和审计日志。
- 通过 Docker Compose 一键启动。
- 使用示例业务数据库演示真实业务问题，不使用真实个人信息。

## 4. 第一版非目标

第一版不做：

- 多租户。
- 复杂权限系统。
- 商业 BI 看板。
- 多模型平台。
- 工作流编排平台。
- 向量知识库。
- 简历、客服、知识库、报表等其他业务模块的功能实现。
- 自动执行模型生成 SQL。
- 连接用户生产数据库。
- 数据写入、数据修改、表结构变更、授权变更。

## 5. 推荐代码目录

```text
spring-ai-business-copilot/
  pom.xml
  app/
    business-copilot-app/
      src/main/java/...
      src/main/resources/
  platform/
    ai-core/
    ai-guardrails/
    ai-tool-audit/
    common-web/
  modules/
    data-copilot/
      src/main/java/...
      src/main/resources/
  examples/
    docker-compose.yml
    sample-data/
  docs/
```

说明：

- `app/business-copilot-app` 是唯一启动应用，负责 Spring Boot 启动、Web/API 入口和配置装配。
- `modules/data-copilot` 承载 Data Copilot 业务流程、领域对象、应用服务和控制器。
- `platform/ai-core` 负责 Spring AI 调用、PromptTemplate 管理、模型返回解析。
- `platform/ai-guardrails` 负责 SQL 安全、只读校验、敏感字段识别与脱敏策略。
- `platform/ai-tool-audit` 负责查询审计、工具调用审计模型与持久化。
- `platform/common-web` 负责统一响应、异常、分页和 Web 错误输出。
- 暂不创建空转的复杂平台层；每个通用层都必须被 Data Copilot 真实使用。

## 6. 第一版功能拆解

### 6.1 示例业务数据库

目标：

- 提供一个贴近真实内部系统的样例电商或 SaaS 业务数据库。
- 支持常见统计问题，例如销售额、退款率、新用户、渠道转化、客单价。

建议表：

| 表名 | 业务含义 | 关键字段 |
| --- | --- | --- |
| `customers` | 客户 | `id`, `name`, `email`, `phone`, `segment`, `created_at` |
| `products` | 商品 | `id`, `name`, `category`, `price`, `status` |
| `orders` | 订单 | `id`, `customer_id`, `order_no`, `channel`, `status`, `total_amount`, `created_at` |
| `order_items` | 订单明细 | `id`, `order_id`, `product_id`, `quantity`, `unit_price` |
| `refunds` | 退款 | `id`, `order_id`, `amount`, `reason`, `created_at` |
| `marketing_events` | 营销事件 | `id`, `channel`, `campaign_name`, `visits`, `signups`, `orders`, `event_date` |
| `query_audit_logs` | 查询审计 | 见审计数据模型 |

边界：

- 示例数据必须是假数据。
- 邮箱、手机号使用明显示例值，例如 `user001@example.com`、`13800000001`。
- 不放真实身份证、真实 token、真实密钥。
- 第一版只支持一个示例数据源。

### 6.2 Schema 上下文管理

目标：

- 从数据库读取表、字段、类型、主外键、注释和样例描述。
- 生成 LLM 可理解的 schema 上下文。
- 限制模型只能基于白名单 schema 生成 SQL。

功能：

- 读取数据库 metadata。
- 维护 Data Copilot 可访问表白名单。
- 维护字段业务描述。
- 标记敏感字段。
- 构造 prompt schema section。
- 提供 schema 预览接口。

边界：

- 不允许模型访问白名单外的表。
- 不把审计表暴露给自然语言查询。
- 不在 prompt 中放数据库密码、连接串等敏感配置。
- 对 schema 上下文做长度控制，第一版不做复杂 RAG。

建议实现：

- `SchemaContextService`
- `SchemaMetadataRepository`
- `SchemaContext`
- `TableSchema`
- `ColumnSchema`
- `SensitiveColumnPolicy`

### 6.3 自然语言转 SQL

目标：

- 使用 Spring AI 根据用户问题和 schema 上下文生成 SQL 草稿。
- 模型输出必须是结构化结果，包含 SQL、解释、假设和风险提示。

输入：

- 用户业务问题。
- schema 上下文。
- SQL 安全规则。
- 结果行数限制。

输出：

```json
{
  "sql": "select ... limit 100",
  "summary": "该 SQL 用于统计上个月销售额。",
  "assumptions": ["按 orders.created_at 判断月份"],
  "warnings": []
}
```

边界：

- 只允许生成单条 `SELECT` 或只读 `WITH ... SELECT`。
- 默认必须带 `LIMIT`，聚合查询可不强制。
- 不允许生成多语句。
- 不允许生成注释里夹带危险语句。
- 不允许生成数据修改、DDL、授权、存储过程、函数创建语句。
- 不允许使用白名单外表和字段。

建议实现：

- `SqlGenerationService`
- `PromptTemplateService`
- `AiChatService`
- `GeneratedSqlCandidate`
- `prompts/data-copilot/sql-generation.st`

### 6.4 SQL 只读校验与 Guardrails

目标：

- 对模型生成 SQL 进行多层安全校验。
- 校验通过后才允许进入用户确认环节。

必须校验：

- 是否单语句。
- 是否只读查询。
- 是否包含禁止关键字。
- 是否访问白名单外表。
- 是否访问敏感字段。
- 是否缺少行数限制。
- 是否存在明显高风险表达式。

禁止关键字：

- `insert`
- `update`
- `delete`
- `drop`
- `alter`
- `truncate`
- `create`
- `grant`
- `revoke`
- `merge`
- `call`
- `execute`

建议校验顺序：

1. 基础文本规范化。
2. 多语句检测。
3. SQL parser AST 检查。
4. 禁止关键字检查。
5. 表字段白名单检查。
6. limit 和 timeout 策略检查。
7. 敏感字段策略检查。

边界：

- 不能只靠字符串 contains 做最终判断。
- SQL 安全逻辑必须可单元测试。
- 校验失败必须返回明确原因。
- 校验失败不得执行 SQL。
- 第一版敏感字段可以默认脱敏后展示，也可以阻断直接查询高风险字段；推荐：对 `password`, `token`, `secret`, `id_card` 阻断，对 `email`, `phone` 脱敏。

建议实现：

- `SqlGuardrailService`
- `SqlReadOnlyValidator`
- `SqlWhitelistValidator`
- `SqlLimitValidator`
- `SensitiveFieldValidator`
- `SqlValidationResult`
- `SqlViolation`

### 6.5 SQL 执行前确认

目标：

- 用户必须看到生成 SQL 和校验结果。
- 用户点击确认后才执行查询。

流程：

1. 用户提交自然语言问题。
2. 后端生成 SQL 并完成 guardrails 校验。
3. 前端展示 SQL、解释、假设、风险提示和校验状态。
4. 用户确认执行。
5. 后端使用确认 token 或 request id 执行对应 SQL。

边界：

- 不允许前端改写 SQL 后直接执行。
- 执行接口必须根据服务端保存的候选 SQL 执行，不能信任客户端传回的 SQL 文本。
- 候选 SQL 设置过期时间，例如 10 分钟。
- 校验未通过的 SQL 不生成可执行确认 token。

建议实现：

- `SqlCandidateStore`，第一版可用内存实现，后续可落库。
- `SqlConfirmationService`
- `SqlCandidate`
- `confirmationToken`

### 6.6 查询执行

目标：

- 执行通过确认的只读 SQL。
- 返回结构化表格数据。

输出：

```json
{
  "columns": [
    {"name": "product_name", "type": "varchar"},
    {"name": "refund_rate", "type": "decimal"}
  ],
  "rows": [
    {"product_name": "Starter Plan", "refund_rate": "0.08"}
  ],
  "rowCount": 1,
  "truncated": false
}
```

边界：

- 设置 query timeout。
- 设置 max rows。
- 只使用只读数据源用户。
- 不支持导出大文件。
- 不支持长时间后台任务。
- SQL 执行异常要转为用户可理解错误，同时记录审计。

建议实现：

- `ReadOnlyQueryExecutor`
- `QueryResultTable`
- `QueryColumn`
- `QueryExecutionException`

### 6.7 查询结果表格

目标：

- 在 Web 页面展示查询结果列、行和基础状态。

功能：

- 表格展示列名和行数据。
- 显示行数和是否截断。
- 显示 SQL、问题和 AI 解释。
- 空结果展示友好提示。
- 错误结果展示可理解原因。

边界：

- 第一版不做复杂 BI 图表。
- 第一版不做拖拽式报表。
- 第一版不做用户自定义看板。
- 第一版可以提供简单 CSV 下载，但不是必须项。

### 6.8 敏感字段脱敏

目标：

- 查询结果返回前对敏感字段进行脱敏。

默认敏感字段：

- `phone`
- `email`
- `id_card`
- `password`
- `token`
- `secret`

策略：

| 字段类型 | 策略 |
| --- | --- |
| `phone` | 保留前三后四，中间替换为 `****` |
| `email` | 保留首字符和域名，中间替换为 `***` |
| `id_card` | 默认不允许查询；如出现则整体替换为 `******` |
| `password` | 阻断查询 |
| `token` | 阻断查询 |
| `secret` | 阻断查询 |

边界：

- 脱敏发生在结果返回前。
- 审计日志不记录完整敏感结果。
- 第一版只做字段名规则和 schema 标记，不做复杂内容识别。

建议实现：

- `SensitiveDataMasker`
- `MaskingPolicy`
- `MaskedQueryResult`

### 6.9 AI 结果解释

目标：

- 对查询结果进行业务语言解释。
- 帮助非技术用户理解数据含义。

输入：

- 用户原始问题。
- 已确认执行的 SQL。
- 查询结果摘要。
- 行列信息。

输出：

- 简短结论。
- 关键数字。
- 可选观察点。
- 必要时说明数据限制。

边界：

- 不能编造查询结果中不存在的数据。
- 不能给出未被数据支持的确定性结论。
- 如果结果为空，要说明没有命中数据，而不是编造原因。
- 解释 prompt 必须集中管理。

建议实现：

- `ResultExplanationService`
- `QueryResultSummarizer`
- `prompts/data-copilot/result-explanation.st`

### 6.10 查询审计日志

目标：

- 记录从问题、生成 SQL、校验、确认、执行到解释的关键链路。

建议字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 审计 ID |
| `request_id` | 请求 ID |
| `user_question` | 用户问题 |
| `generated_sql` | 模型生成 SQL |
| `final_sql` | 实际执行 SQL |
| `validation_status` | 校验状态 |
| `validation_errors` | 校验错误 |
| `confirmed` | 是否用户确认 |
| `execution_status` | 执行状态 |
| `row_count` | 返回行数 |
| `error_message` | 错误信息 |
| `model_name` | 模型名称 |
| `latency_ms` | 总耗时 |
| `created_at` | 创建时间 |

边界：

- 必须记录失败请求。
- 不记录完整敏感结果。
- 不暴露审计表给自然语言查询。
- 第一版可使用系统默认用户，不做复杂用户体系。

建议实现：

- `ToolAuditService`
- `QueryAuditLog`
- `QueryAuditRepository`
- `AuditEvent`

## 7. 建议 API

### 7.1 页面入口

```http
GET /
```

返回 Data Copilot Web 页面。

### 7.2 生成 SQL 候选

```http
POST /api/data-copilot/sql-candidates
Content-Type: application/json

{
  "question": "上个月销售额最高的 10 个商品是什么？"
}
```

响应：

```json
{
  "requestId": "req_001",
  "candidateId": "cand_001",
  "question": "上个月销售额最高的 10 个商品是什么？",
  "sql": "select ... limit 10",
  "summary": "统计上个月商品销售额排名。",
  "assumptions": ["按订单创建时间统计"],
  "validation": {
    "passed": true,
    "violations": []
  },
  "executable": true
}
```

### 7.3 确认执行 SQL

```http
POST /api/data-copilot/sql-candidates/{candidateId}/execute
Content-Type: application/json

{
  "confirmationToken": "confirm_001"
}
```

响应：

```json
{
  "requestId": "req_001",
  "sql": "select ... limit 10",
  "table": {
    "columns": [],
    "rows": [],
    "rowCount": 0,
    "truncated": false
  },
  "explanation": "上个月销售额最高的商品是 ...",
  "auditId": 1
}
```

### 7.4 Schema 预览

```http
GET /api/data-copilot/schema
```

返回可查询 schema 摘要，不返回数据库连接敏感信息。

### 7.5 审计日志列表

```http
GET /api/data-copilot/audit-logs?page=0&size=20
```

第一版可只给开发者或本地演示使用。

## 8. UI 范围

第一版 UI 不是营销页，第一屏就是 Data Copilot 工作台。

页面区域：

- 问题输入区。
- SQL 候选展示区。
- Guardrails 校验结果区。
- 用户确认按钮。
- 查询结果表格区。
- AI 解释区。
- 最近审计记录入口或列表。

边界：

- 不做复杂后台导航。
- 不做商业 BI 首页。
- 不做登录注册。
- 不做多模块切换，只保留未来模块入口占位说明也可以不展示。

## 9. 测试要求

必须有测试：

- SQL 只读校验通过：普通 `select`、聚合查询、`with ... select`。
- SQL 只读校验拒绝：`insert`、`update`、`delete`、`drop`、`alter`、`truncate`、`create`、`grant`、`revoke`。
- 多语句拒绝。
- 白名单外表拒绝。
- 敏感字段策略。
- 查询结果脱敏。
- 审计日志在成功和失败场景均写入。

建议有测试：

- schema 上下文生成。
- prompt 模板渲染。
- SQL 候选确认 token 过期。
- 查询异常转用户友好错误。

## 10. Docker Compose 一键启动

必须包含：

- 应用服务。
- PostgreSQL 数据库。
- 初始化 schema 和示例数据。

可选：

- Ollama 本地模型服务。

边界：

- 默认配置不能提交真实 API Key。
- 使用环境变量读取模型 API Key。
- README 中说明如何选择 OpenAI 兼容模型或本地模型。

## 11. AI 代码生成提示词

可以把以下内容交给 AI 生成代码：

```text
请基于 Spring Boot、Spring AI 和 Maven 多模块创建 Spring AI Business Copilot 第一版。

包名要求：
- Maven groupId 使用 dev.qcoding。
- Java 包名全部以 dev.qcoding.businesscopilot 开头。
- 不要使用 io.github、com.example 或默认 demo 包名。

只实现 Data Copilot：数据库查询助手。不要实现多租户、复杂权限、BI 看板、多模型平台、工作流编排，也不要实现 Resume、Support、Knowledge、Report 模块的业务功能，只保留架构可扩展边界。

模块结构：
- app/business-copilot-app：启动应用、Web 页面、API 入口和配置装配。
- platform/ai-core：Spring AI ChatClient 封装、prompt 模板加载、结构化模型输出解析。
- platform/ai-guardrails：SQL 只读校验、白名单表字段校验、limit 校验、敏感字段识别和结果脱敏。
- platform/ai-tool-audit：查询审计日志模型、Repository 和 AuditService。
- platform/common-web：统一 API 响应、异常处理和分页。
- modules/data-copilot：Data Copilot 业务流程，包括 schema 上下文、自然语言转 SQL、SQL 候选确认、只读查询执行、结果解释。

第一版必须实现：
1. Spring Boot 后端。
2. Spring AI 调用。
3. 示例 PostgreSQL 业务数据库和 Flyway 初始化数据。
4. schema 上下文管理。
5. 自然语言转 SQL。
6. SQL 只读校验。
7. SQL 执行前确认。
8. 查询结果表格。
9. 查询结果敏感字段脱敏。
10. AI 结果解释。
11. 查询审计日志。
12. Docker Compose 一键启动。

安全要求：
- 只允许单条 SELECT 或 WITH SELECT。
- 禁止 insert、update、delete、drop、alter、truncate、create、grant、revoke、merge、call、execute。
- SQL 必须通过 parser 和规则校验，不能只靠字符串 contains。
- 查询前必须展示 SQL，用户确认后才执行。
- 执行接口不能信任客户端传回的 SQL，只能执行服务端保存且未过期的 SQL 候选。
- 查询必须记录审计日志，失败也要记录。
- phone、email 脱敏；id_card、password、token、secret 默认阻断或完全遮蔽。
- 示例数据不能包含真实个人信息。

请补充必要的单元测试，尤其是 SQL guardrails 和脱敏逻辑测试。
```

## 12. 第一版验收清单

- 能通过 Docker Compose 启动应用和数据库。
- 打开首页就是 Data Copilot 查询工作台。
- 用户输入自然语言问题后，系统生成 SQL 候选。
- 页面展示 SQL、假设、校验状态和确认按钮。
- 校验失败时不能执行。
- 用户确认后执行查询。
- 查询结果以表格展示。
- 敏感字段已脱敏或被阻断。
- AI 输出业务解释。
- 成功和失败请求都能在审计日志中看到。
- SQL guardrails 有单元测试。
- README 保持英文入口，中文 README 保持中文入口。
