# 技术栈推荐

## 1. 选型原则

第一版技术栈服务于 Data Copilot 闭环，而不是提前建设大平台。

选型原则：

- Java / Spring 技术栈优先，符合项目定位。
- 少引入前端复杂度，优先保证业务闭环可运行。
- SQL 安全、审计、prompt 管理和测试能力必须扎实。
- 默认可本地启动，便于个人开发者和中小团队学习改造。
- 版本选择以创建项目时 Spring Initializr 和官方 BOM 的稳定版本为准。

## 2. 后端技术栈

| 类别 | 推荐 | 用途 | 说明 |
| --- | --- | --- | --- |
| JDK | Java 21 LTS | 运行时和编译 | 兼顾长期支持和生态兼容性 |
| 构建 | Maven 多模块 | 模块化工程 | 适合 Spring Boot 示例项目和文档化结构 |
| 应用框架 | Spring Boot 4.1.x | Web、配置、依赖装配 | 当前项目骨架使用 `4.1.0` |
| AI 集成 | Spring AI | ChatClient、模型适配、PromptTemplate | 符合项目名称和定位 |
| Web | Spring Web MVC | REST API 和页面入口 | 当前 Boot 4 依赖使用 `spring-boot-starter-webmvc` |
| 数据访问 | Spring JDBC + NamedParameterJdbcTemplate | 动态只读 SQL 执行 | 比 JPA 更适合执行模型生成的查询 SQL |
| 管理端点 | Spring Boot Actuator | 健康检查 | Docker Compose 和本地调试需要 |
| 校验 | Jakarta Validation | 请求参数校验 | 用于 question 等输入校验 |

不推荐：

- 第一版不使用 Spring WebFlux，避免提高理解和调试成本。
- 第一版不使用复杂 ORM 执行动态 SQL，查询执行用 JDBC 更直接。
- 第一版不做独立网关服务。

## 3. AI 与 Prompt

| 类别 | 推荐 | 用途 | 说明 |
| --- | --- | --- | --- |
| Spring AI Client | `ChatClient` | 统一模型调用 | 封装在 `platform/ai-core` |
| Prompt 管理 | resource 模板文件 | SQL 生成和结果解释 | 放在 `src/main/resources/prompts/...` |
| 输出格式 | JSON 结构化输出 | SQL 候选解析 | 降低自然语言解析不确定性 |
| 模型供应商 | OpenAI 兼容接口优先，可选 Ollama | 本地和云端二选一 | API Key 通过环境变量配置 |

建议 prompt 文件：

```text
platform/ai-core/src/main/resources/prompts/
  data-copilot/
    sql-generation.st
    result-explanation.st
```

边界：

- service 代码中不能散落大段 prompt 文本。
- 模型输出不能直接进入 SQL 执行。
- 模型名、base url、api key 必须配置化。

## 4. 数据库与迁移

| 类别 | 推荐 | 用途 | 说明 |
| --- | --- | --- | --- |
| 数据库 | PostgreSQL | 示例业务数据库 | 贴近真实业务系统，Docker 运行方便 |
| 迁移 | Flyway | 初始化表结构和样例数据 | 可重复启动和重建 |
| 查询执行 | 只读数据库用户 | 降低误操作风险 | Docker 初始化时创建只读用户 |
| 连接池 | HikariCP | 默认连接池 | Spring Boot 默认集成 |

建议 Flyway 文件：

```text
app/business-copilot-app/src/main/resources/db/migration/
  V1__create_sample_business_tables.sql
  V2__insert_sample_business_data.sql
  V3__create_query_audit_logs.sql
```

边界：

- 审计表不进入 Data Copilot 可查询白名单。
- 示例数据不包含真实个人信息。
- 查询数据源尽量使用只读数据库用户。

## 5. SQL 安全技术

| 类别 | 推荐 | 用途 | 说明 |
| --- | --- | --- | --- |
| SQL Parser | JSQLParser | AST 级 SQL 检查 | 避免只靠字符串匹配 |
| 规则校验 | 自研 validator chain | 项目安全边界 | 单语句、只读、白名单、limit、敏感字段 |
| 超时控制 | JDBC query timeout | 防止慢查询拖垮演示环境 | 配置默认值 |
| 行数限制 | SQL limit + JDBC max rows | 防止大结果集 | 默认 100，最大 500 或 1000 |

建议 validator：

- `SingleStatementValidator`
- `ReadOnlyStatementValidator`
- `ForbiddenKeywordValidator`
- `SchemaWhitelistValidator`
- `LimitRequiredValidator`
- `SensitiveFieldValidator`

边界：

- Parser 失败默认拒绝执行。
- 多语句默认拒绝。
- 校验失败必须记录审计。

## 6. 前端技术栈

第一版推荐轻量服务端页面：

| 类别 | 推荐 | 用途 | 说明 |
| --- | --- | --- | --- |
| 模板 | Thymeleaf | 首页工作台 | 与 Spring Boot 集成简单 |
| 交互 | HTMX 或少量原生 JavaScript | 提交问题、确认执行、局部刷新 | 避免 React/Vue 构建链路 |
| 样式 | CSS + 简单设计变量 | 工作台界面 | 不做营销页和复杂 UI 框架 |

页面组件：

- 问题输入框。
- 生成 SQL 按钮。
- SQL 展示区域。
- Guardrails 校验结果。
- 确认执行按钮。
- 查询结果表格。
- AI 解释区域。
- 审计日志列表入口。

替代方案：

- 如果后续希望分离前后端，可再引入 React 或 Vue。
- 第一版不推荐引入 SPA，因为会分散 Data Copilot 后端闭环重点。

## 7. 测试技术栈

| 类别 | 推荐 | 用途 |
| --- | --- | --- |
| 单元测试 | JUnit 5 | validator、masker、service 测试 |
| 断言 | AssertJ | 可读性更好 |
| Mock | Mockito | AI client 和 repository mock |
| 集成测试 | Testcontainers PostgreSQL | 验证数据库迁移和查询执行 |
| Web 测试 | Spring Boot Test + MockMvc | API 行为验证 |

最低测试覆盖目标：

- SQL guardrails。
- 敏感字段脱敏。
- schema 白名单。
- 查询执行异常。
- 审计日志。

## 8. Docker 与本地运行

推荐：

```text
examples/docker-compose.yml
```

服务：

- `business-copilot-app`
- `postgres`
- 可选 `ollama`

环境变量：

```text
SPRING_AI_OPENAI_API_KEY=
SPRING_AI_OPENAI_BASE_URL=
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=
BUSINESS_COPILOT_QUERY_MAX_ROWS=100
BUSINESS_COPILOT_QUERY_TIMEOUT_SECONDS=10
```

边界：

- 不提交真实 API Key。
- 本地模型作为可选路径，不作为第一版必须依赖。

## 9. 依赖建议

根 `pom.xml`：

- 使用 Spring Boot parent 或 dependency management。
- 使用 Spring AI BOM 管理 AI 相关依赖。
- 管理 Maven module 列表。
- Maven `groupId` 使用 `dev.qcoding`，Java 包名统一使用 `dev.qcoding.businesscopilot` 开头。

核心依赖方向：

```text
spring-boot-starter-webmvc
spring-boot-starter-thymeleaf
spring-boot-starter-validation
spring-boot-starter-jdbc
spring-boot-starter-actuator
spring-ai-starter-model-openai
postgresql
flyway-core
jsqlparser
spring-boot-starter-test
testcontainers-postgresql
```

说明：

- Spring AI 依赖 artifact 名称可能随版本调整，创建项目时以官方参考文档和 BOM 为准。
- 不把具体版本硬编码进规划文档，避免文档和官方稳定版本脱节。

## 10. 后续模块预留方式

第一版只创建必要目录和边界，不实现后续模块业务。

可预留：

```text
modules/
  data-copilot/
  resume-copilot/
  support-copilot/
```

如果预留空模块会增加构建复杂度，也可以先只在文档中预留，代码只实现 `data-copilot`。

后续模块复用平台能力：

- Resume Copilot 复用 `ai-core`、`ai-guardrails`、`ai-tool-audit`。
- Support Copilot 复用 `ai-core`、`ai-tool-audit` 和后续知识检索能力。
- Knowledge Copilot 后续再引入 embedding、vector store 和文档解析。
- Report Copilot 后续再引入导出能力。

## 11. 参考链接

- Spring AI Reference: https://docs.spring.io/spring-ai/reference/
- Spring Boot Reference: https://docs.spring.io/spring-boot/
- Spring Initializr: https://start.spring.io/
- PostgreSQL Docker Image: https://hub.docker.com/_/postgres
- Flyway Documentation: https://documentation.red-gate.com/fd/flyway-documentation-138346877.html
- JSQLParser: https://jsqlparser.github.io/JSqlParser/
- Testcontainers for Java: https://java.testcontainers.org/
