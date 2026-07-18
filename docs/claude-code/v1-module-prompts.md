# Claude Code 分模块 Prompt

本文档把第一版拆成可以逐段交给 Claude Code 执行的模块任务。

执行建议：

1. 每次只执行一个编号任务。
2. 每个任务完成后运行相关测试。
3. 不要越界提前实现后续任务。
4. 任何实现都必须遵守 `dev.qcoding.businesscopilot` 包名前缀。

## 0. 项目骨架校准 Prompt

```text
请检查当前 Maven 多模块项目骨架，并只做必要校准，不实现业务功能。

要求：
- Maven groupId 必须统一为 dev.qcoding。
- Java 包名必须以 dev.qcoding.businesscopilot 开头。
- 保留模块：
  - app/business-copilot-app
  - platform/common-web
  - platform/ai-core
  - platform/ai-guardrails
  - platform/ai-tool-audit
  - modules/data-copilot
- 使用 Java 21、Spring Boot 4.1.x、Spring AI 2.0.x。
- app 模块使用 spring-boot-starter-webmvc、actuator、validation、thymeleaf。
- ai-core 模块引入 spring-ai-starter-model-openai。
- 不要写 Data Copilot 业务逻辑。
- 补充最小启动类和 application.yml。
- application.yml 中 Spring AI 默认 chat model 为 none，避免没有 API Key 时启动失败。

完成后运行：
- mvn -q -DskipTests compile
```

边界：

- 不添加 Controller。
- 不添加数据库迁移。
- 不添加 Docker Compose。
- 不实现 SQL guardrails。

## 1. common-web 模块 Prompt

```text
请实现 platform/common-web 模块的基础 Web 公共能力。

包名：
- dev.qcoding.businesscopilot.commonweb

目标：
- 提供统一 API 响应结构。
- 提供统一错误结构。
- 提供基础分页响应。
- 提供全局异常处理能力。

请实现：
- ApiResponse<T>
- PageResponse<T>
- ErrorCode
- BusinessException
- ValidationErrorResponse
- GlobalExceptionHandler

要求：
- ApiResponse 成功时包含 success=true、data、message、timestamp。
- 失败时包含 success=false、errorCode、message、timestamp。
- GlobalExceptionHandler 处理 BusinessException、MethodArgumentNotValidException、通用 Exception。
- 不暴露内部堆栈给前端。
- 类和 public 方法写英文 Javadoc，关键异常处理处增加简短中文注释。
- 不引入业务模块依赖。

测试：
- ApiResponse success/failure 构造。
- BusinessException 映射。
- 参数校验错误结构。
```

边界：

- 不实现认证。
- 不实现权限。
- 不定义 Data Copilot 业务错误以外的大而全错误码体系。

## 2. ai-core 模块 Prompt

```text
请实现 platform/ai-core 模块的基础 AI 能力封装。

包名：
- dev.qcoding.businesscopilot.aicore

目标：
- 封装 Spring AI ChatClient。
- 集中加载 prompt 模板。
- 提供结构化 JSON 输出解析工具。

请实现：
- AiChatService
- PromptTemplateService
- JsonOutputParser
- AiModelProperties
- AiCoreAutoConfiguration

Prompt 文件：
- platform/ai-core/src/main/resources/prompts/data-copilot/sql-generation.st
- platform/ai-core/src/main/resources/prompts/data-copilot/result-explanation.st

要求：
- service 代码中不要写大段 prompt。
- PromptTemplateService 从 classpath 读取模板。
- AiChatService 对外提供 generateText 和 generateJson 两类方法。
- 如果 Spring AI chat model 配置为 none，服务应给出清晰错误，而不是空指针。
- 模型调用异常要转为业务可理解异常。
- JSON 解析失败要返回明确错误。
- Prompt 模板中写明 SQL 只读、单语句、白名单、不得编造等约束。
- Prompt 注释允许中英文混合。

测试：
- prompt 模板可以加载。
- JSON 解析成功和失败。
- chat model 未启用时错误清晰。
```

边界：

- 不在 ai-core 实现 Data Copilot 业务流程。
- 不直接执行 SQL。
- 不直接访问数据库。
- 不实现多模型管理平台。

## 3. ai-guardrails 模块 Prompt

```text
请实现 platform/ai-guardrails 模块。

包名：
- dev.qcoding.businesscopilot.guardrails

目标：
- 校验模型生成 SQL 是否安全。
- 提供敏感字段策略。
- 提供查询结果脱敏能力。

依赖：
- 引入 JSQLParser。

请实现：
- SqlGuardrailService
- SqlValidationResult
- SqlViolation
- SqlViolationCode
- SqlValidator
- SingleStatementValidator
- ReadOnlyStatementValidator
- ForbiddenKeywordValidator
- SchemaWhitelistValidator
- LimitRequiredValidator
- SensitiveFieldValidator
- SensitiveFieldPolicy
- SensitiveDataMasker
- MaskingRule

SQL 安全规则：
- 只允许单条 SELECT 或 WITH SELECT。
- 多语句拒绝。
- Parser 失败拒绝。
- 禁止 insert、update、delete、drop、alter、truncate、create、grant、revoke、merge、call、execute。
- 访问白名单外表拒绝。
- password、token、secret、id_card 默认拒绝。
- phone、email 允许查询但返回前脱敏。
- 非聚合查询默认必须有 limit。
- limit 不得超过配置最大值。

注释要求：
- 每个 validator 类写英文 Javadoc。
- 在只读判断、多语句判断、敏感字段策略处增加中文注释，说明业务安全原因。

测试必须覆盖：
- select 通过。
- with select 通过。
- insert/update/delete/drop/alter/truncate/create/grant/revoke/merge/call/execute 拒绝。
- 多语句拒绝。
- SQL 注释夹带危险关键字拒绝。
- Parser 失败拒绝。
- 白名单外表拒绝。
- 高敏字段拒绝。
- phone/email 脱敏。
- limit 超限拒绝。
```

边界：

- 不能只靠字符串 contains 作为最终校验。
- Parser 和规则链都要使用。
- 校验失败绝不允许执行。
- 不访问模型。
- 不访问业务数据库。

## 4. ai-tool-audit 模块 Prompt

```text
请实现 platform/ai-tool-audit 模块。

包名：
- dev.qcoding.businesscopilot.audit

目标：
- 提供查询和工具调用审计能力。
- 审计成功、失败和用户取消。

请实现：
- AuditEvent
- AuditEventType
- QueryAuditLog
- QueryAuditRepository
- JdbcQueryAuditRepository
- AuditService
- AuditStatus

审计字段至少包含：
- id
- requestId
- userQuestion
- generatedSql
- finalSql
- validationStatus
- validationErrors
- confirmed
- executionStatus
- rowCount
- errorMessage
- modelName
- latencyMs
- createdAt

要求：
- 使用 Spring JDBC 持久化。
- 审计表由 Flyway 创建。
- 失败请求也必须记录。
- 用户取消执行也要记录。
- 不记录完整查询结果。
- 不记录完整敏感字段值。
- 审计日志不能被 Data Copilot schema 白名单暴露给自然语言查询。
- 在不记录敏感结果的位置加中文注释说明原因。

测试：
- 成功事件写入。
- 校验失败事件写入。
- 执行失败事件写入。
- 敏感结果不进入审计记录。
```

边界：

- 不实现复杂审计后台。
- 不做用户权限审计。
- 不接入外部日志平台。

## 5. data-copilot Schema 模块 Prompt

```text
请在 modules/data-copilot 中实现 Schema 上下文管理。

包名：
- dev.qcoding.businesscopilot.datacopilot.schema

目标：
- 读取 PostgreSQL metadata。
- 构造给 LLM 使用的 schema 上下文。
- 维护可查询表白名单和敏感字段标记。

请实现：
- SchemaContextService
- SchemaMetadataRepository
- JdbcSchemaMetadataRepository
- SchemaContext
- TableSchema
- ColumnSchema
- DataCopilotSchemaProperties

要求：
- 默认白名单只包含 customers、products、orders、order_items、refunds、marketing_events。
- query_audit_logs 不允许进入白名单。
- ColumnSchema 包含 name、type、nullable、description、sensitive、maskingStrategy。
- SchemaContextService 输出适合 prompt 的文本摘要。
- 不在 prompt schema 中暴露数据库连接信息。
- schema 文本应控制长度，避免过长。
- 字段描述可以先通过配置或代码内 map 补充。

测试：
- 白名单过滤生效。
- 审计表不进入 schema。
- 敏感字段标记正确。
- prompt schema 文本包含表和字段描述。
```

边界：

- 不做多数据源。
- 不做动态授权。
- 不做 schema RAG。

## 6. data-copilot SQL 生成 Prompt

```text
请在 modules/data-copilot 中实现自然语言转 SQL 的业务服务。

包名：
- dev.qcoding.businesscopilot.datacopilot.generation

目标：
- 根据用户问题和 schema 上下文调用 ai-core 生成 SQL 候选。
- 解析模型结构化输出。
- 生成后立即进入 guardrails 校验。

请实现：
- SqlGenerationService
- GeneratedSqlCandidate
- SqlGenerationRequest
- SqlGenerationResponse
- SqlCandidateValidationSummary

流程：
1. 校验用户问题非空且长度合理。
2. 获取 SchemaContext。
3. 渲染 sql-generation.st。
4. 调用 AiChatService。
5. 解析 JSON。
6. 调用 SqlGuardrailService。
7. 返回 SQL、summary、assumptions、warnings、validation。

要求：
- 模型输出不能直接执行。
- guardrails 不通过时也要把违规原因返回前端。
- requestId 全链路传递。
- 调用失败要写审计。
- 输出 DTO 不包含内部异常堆栈。

测试：
- 正常生成候选。
- 模型 JSON 格式错误。
- guardrails 拒绝时返回违规原因。
```

边界：

- 不执行 SQL。
- 不保存确认 token。
- 不做结果解释。

## 7. data-copilot 执行前确认 Prompt

```text
请实现 SQL 候选确认机制。

包名：
- dev.qcoding.businesscopilot.datacopilot.confirmation

目标：
- 服务端保存通过校验的 SQL 候选。
- 生成 confirmationToken。
- 用户确认后只能执行服务端保存的 SQL。

请实现：
- SqlCandidate
- SqlCandidateStore
- InMemorySqlCandidateStore
- SqlConfirmationService
- SqlCandidateExpiredException
- SqlCandidateNotExecutableException

要求：
- 只有 guardrails 通过的候选才能生成 confirmationToken。
- token 使用安全随机数。
- 候选设置过期时间，默认 10 分钟。
- 执行接口不能信任客户端传回的 SQL。
- 取出候选时必须校验 candidateId、confirmationToken、过期时间、executable。
- 在代码注释中说明为什么不能信任客户端 SQL。

测试：
- 有效 token 可取出 SQL。
- 无效 token 拒绝。
- 过期候选拒绝。
- guardrails 失败候选不能执行。
```

边界：

- 第一版使用内存存储即可。
- 不做 Redis。
- 不做集群会话一致性。

## 8. data-copilot 查询执行 Prompt

```text
请实现只读查询执行和结果表格。

包名：
- dev.qcoding.businesscopilot.datacopilot.query

目标：
- 执行已确认的只读 SQL。
- 返回结构化表格。
- 对结果做脱敏。

请实现：
- ReadOnlyQueryExecutor
- JdbcReadOnlyQueryExecutor
- QueryResultTable
- QueryColumn
- QueryRow
- QueryExecutionProperties
- QueryExecutionException

要求：
- 使用 Spring JDBC。
- 设置 query timeout。
- 设置 max rows。
- 返回 columns、rows、rowCount、truncated。
- 执行前再次调用 guardrails 做防御式校验。
- 查询结果返回前调用 SensitiveDataMasker。
- SQL 执行异常转换成用户可理解错误。
- 失败必须审计。

测试：
- 成功查询返回表格。
- max rows 截断标记正确。
- phone/email 脱敏。
- 执行异常转业务错误。
- 防御式 guardrails 生效。
```

边界：

- 不支持写操作。
- 不支持导出大文件。
- 不支持后台长任务。
- 不支持用户自定义连接池。

## 9. data-copilot 结果解释 Prompt

```text
请实现查询结果 AI 解释。

包名：
- dev.qcoding.businesscopilot.datacopilot.explanation

目标：
- 根据用户问题、已执行 SQL 和脱敏后的结果摘要生成业务解释。

请实现：
- ResultExplanationService
- QueryResultSummarizer
- ResultExplanationRequest
- ResultExplanationResponse

要求：
- 使用 result-explanation.st prompt。
- 解释只基于查询结果，不得编造。
- 空结果要说明未查询到匹配数据。
- 大结果只传摘要给模型，不把完整结果全部塞进 prompt。
- 模型调用失败时返回降级解释，不影响表格结果展示。

Prompt 必须约束：
- Do not invent numbers.
- Only explain facts present in the result table.
- If result is empty, say no matching data was found.
- Use concise business language.
- 中文问题优先中文回答，英文问题优先英文回答。

测试：
- 结果摘要生成正确。
- 空结果解释。
- 模型失败降级。
```

边界：

- 不给经营建议做强结论。
- 不把敏感原始值发给模型。

## 10. data-copilot API Prompt

```text
请实现 Data Copilot REST API。

包名：
- dev.qcoding.businesscopilot.datacopilot.web

API：
- GET /api/data-copilot/schema
- POST /api/data-copilot/sql-candidates
- POST /api/data-copilot/sql-candidates/{candidateId}/execute
- GET /api/data-copilot/audit-logs?page=0&size=20

请求响应：
- 使用 common-web 的 ApiResponse。
- 参数使用 Jakarta Validation。
- 错误不要暴露堆栈。

流程：
- /sql-candidates：生成 SQL，校验，保存可执行候选，返回 SQL 和校验结果。
- /execute：校验确认 token，执行服务端 SQL，脱敏，解释，审计，返回表格和解释。
- /schema：返回白名单 schema 摘要。
- /audit-logs：返回最近审计。

测试：
- 生成 SQL API 参数校验。
- 校验失败不能生成可执行 token。
- execute 不接受客户端 SQL。
- execute 成功返回 table + explanation。
```

边界：

- 不做登录注册。
- 不做权限系统。
- 不做管理后台。

## 11. app 数据库和 Docker Prompt

```text
请在 app/business-copilot-app 和 examples 中补充数据库迁移和 Docker Compose。

目标：
- Docker Compose 一键启动 PostgreSQL 和应用。
- Flyway 初始化示例业务库和审计表。

请实现：
- app/business-copilot-app/src/main/resources/db/migration/V1__create_sample_business_tables.sql
- app/business-copilot-app/src/main/resources/db/migration/V2__insert_sample_business_data.sql
- app/business-copilot-app/src/main/resources/db/migration/V3__create_query_audit_logs.sql
- examples/docker-compose.yml
- Dockerfile 或 app Docker 构建配置

示例表：
- customers
- products
- orders
- order_items
- refunds
- marketing_events
- query_audit_logs

要求：
- 示例数据为假数据。
- 邮箱使用 example.com。
- 手机号使用测试号段，不使用真实个人信息。
- 不插入真实 id_card、password、token、secret。
- Docker 环境变量读取模型配置。
- 不提交真实 API Key。
- 数据库应用用户尽量只读查询业务表；迁移用户可建表。

测试：
- Flyway 能成功迁移。
- Testcontainers PostgreSQL 能启动并查询示例数据。
```

边界：

- 不连接生产库。
- 不做云部署脚本。
- 不做 Kubernetes。

## 12. 前端工作台 Prompt

```text
请实现第一版 Data Copilot 前端工作台。

位置：
- app/business-copilot-app/src/main/resources/templates/index.html
- app/business-copilot-app/src/main/resources/static/css/app.css
- app/business-copilot-app/src/main/resources/static/js/app.js

技术：
- Thymeleaf 页面。
- 原生 JavaScript fetch。
- CSS 变量和响应式布局。

页面目标：
- 第一屏就是可用的查询工作台。
- 不做营销页。
- 不做复杂 SPA。

页面区域：
- Header：项目名和简短副标题。
- Question panel：自然语言问题输入框、生成 SQL 按钮。
- SQL candidate panel：SQL、summary、assumptions、warnings。
- Guardrails panel：通过或失败、违规原因。
- Confirmation action：确认执行按钮。
- Result table：列、行、rowCount、truncated。
- Explanation panel：AI 业务解释。
- Audit preview：最近审计记录。
- Error toast 或 alert。

交互：
- 点击生成 SQL 调用 POST /api/data-copilot/sql-candidates。
- 校验失败禁用确认执行按钮。
- 点击确认执行调用 POST /api/data-copilot/sql-candidates/{candidateId}/execute。
- 执行时显示 loading。
- 空结果显示友好空状态。
- 表格横向滚动。
- SQL 区域等宽字体，可复制。
- 网络错误给出可理解提示。

视觉：
- 工具型后台风格，清晰、克制、可扫描。
- 避免大面积渐变和营销 hero。
- 卡片只用于具体工具面板，不要卡片套卡片。
- 手机端纵向布局，桌面端两列布局。
- 文案中英文适度混合：界面主文案中文，技术标签可英文。

测试：
- 可以用 MockMvc 验证首页返回 200。
- 可以为 app.js 保持简单，不需要复杂前端测试框架。
```

边界：

- 不引入 React/Vue。
- 不做登录页。
- 不做多模块导航。
- 不做 BI 图表。

## 13. README 和运行文档 Prompt

```text
请更新 README.md 和 README.zh-CN.md。

要求：
- README.md 保持英文入口。
- README.zh-CN.md 保持中文入口。
- 说明第一版只实现 Data Copilot。
- 说明如何本地运行。
- 说明如何配置 Spring AI OpenAI 兼容模型。
- 说明如何使用 Docker Compose。
- 说明默认只读、安全确认、审计和脱敏。
- 不夸大功能，不写未实现模块的使用教程。

同时新增：
- docs/data-copilot.md

docs/data-copilot.md 内容：
- 模块业务价值。
- 核心流程。
- API 列表。
- 安全边界。
- 示例问题。
- 已知限制。
```

边界：

- 不写商业宣传文案。
- 不承诺生产可直接使用。
- 不写未实现功能的操作步骤。

