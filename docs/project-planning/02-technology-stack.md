# 技术栈与框架演进

> 更新日期：2026-07-16

## 1. 选型结论

项目继续采用 Java 21、Spring Boot 4.1、Spring AI 2.0、PostgreSQL、Flyway 和 Maven 多模块。

持久层统一使用模块内显式 Spring JDBC Repository。普通 CRUD、条件状态更新、批量写入、模型生成 SQL 的动态只读执行、数据库元数据和 pgvector 距离查询分别使用窄 SQL，避免 Mapper 扫描和隐式 ORM 行为影响模块独立装配。

## 2. 版本基线

| 类别 | 版本/方案 | 状态 |
|---|---|---|
| JDK | Java 21 LTS | 已使用 |
| Spring Boot | 4.1.0 | 已使用 |
| Spring Framework | 7.x，由 Boot BOM 管理 | 已使用 |
| Spring AI | 2.0.0 BOM | 已使用 |
| PostgreSQL | 16 | 已使用 |
| pgvector | PostgreSQL extension | 已使用 |
| MySQL Connector/J | Boot BOM | 仅用于 Data 外部查询目标 |
| Flyway | 由 Boot BOM 管理 | 已使用 |
| JSQLParser | 4.9 | 已用于 Data Guardrails |
| Maven | Wrapper，兼容 Maven 3.9+ | 已使用 |

版本规则：

- Spring Boot、Spring AI 和数据库驱动通过 BOM 管理。
- 子模块不单独写框架版本。
- 不引入未被业务模块实际使用的 ORM/starter。
- JSQLParser 升级必须重新验证 Data 安全规则。

## 3. Spring AI 2.0

### 当前合理部分

- `ChatClient.Builder` 由 Spring AI 自动配置提供。
- `EmbeddingModel` 作为 embedding provider 抽象。
- Prompt 使用 resource 模板。
- AI 服务在模型未启用时给出业务错误。

### 目标用法

- 文本回答：`ChatClient.call().content()`。
- 结构化回答：`ChatClient.call().entity(Type.class, spec -> spec.validateSchema())`。
- 原生 structured output 仅在具体 provider 验证支持后启用。
- system instruction 与用户数据分开传递。
- 使用 `ChatResponse`/`ChatClientResponse` 记录模型元数据。
- 使用 Spring AI observation 与 Micrometer，不自建重复 tracing 框架。

### Jackson 规则

Spring Boot 4.1 和 Spring AI 2.0 的应用级 JSON 映射使用 Jackson 3。Spring AI OpenAI starter 当前通过 OpenAI SDK 传递引入 Jackson 2 compatibility databind，业务代码不应直接依赖或使用它。

目标：

- 删除 `com.fasterxml.jackson.core:jackson-databind` 显式依赖（已完成）。
- 删除直接 new Jackson 2 `ObjectMapper` 的代码（已完成）。
- 不强行排除 provider SDK 的传递 Jackson 2 兼容依赖，除非已完成 provider 级兼容性验证。
- 优先让 Spring AI structured output 负责模型 JSON 映射。
- Web、AI 和审计统一使用 Boot 管理的 Jackson 3。

## 4. Spring JDBC Repository

### 适用原则

- 每个模块保留业务 Repository 接口和显式 SQL 实现。
- 状态机使用 `WHERE id = ? AND status = ? AND expires_at > ?` 条件更新。
- 动态业务查询只进入 Data Copilot 的专用只读执行器。
- `information_schema` 与 pgvector 查询保留可审查 SQL。
- 批量写入和审计字段由模块 Repository 明确控制。

### 使用约束

- Flyway 是唯一 DDL 来源。
- 不建立全局 BaseEntity、通用 Repository 框架或隐式扫描。
- SQL、token、provider 异常和敏感正文不得进入客户端错误。
- 平台 DataSource 与外部业务查询 DataSource 必须通过限定 Bean 分离。

## 5. 数据库与事务

| 场景 | 技术 |
|---|---|
| 业务 CRUD/条件状态更新 | Spring JDBC |
| 动态只读 SQL | JdbcTemplate/StatementCallback |
| schema metadata | JdbcTemplate |
| pgvector 写入与检索 | JdbcTemplate + PostgreSQL vector |
| 数据库迁移 | Flyway |
| 事务 | Spring `@Transactional`，同一 DataSource/TransactionManager |

事务边界放在业务 service，不放在 controller 或 mapper。

Knowledge 文档上传必须保证 document、chunks 和 embeddings 的一致性；embedding 外部调用是否放在数据库事务内，需要在迁移时评估长事务问题，优先采用“先解析/向量化，再短事务落库”或明确补偿策略。

## 6. Web 与前端

- Spring Web MVC。
- Thymeleaf 作为工作台页面入口。
- 原生 JavaScript 按 Copilot 模块拆文件。
- 统一 `ApiResponse` 和异常映射。
- 当前不引入 React/Vue 构建链。

如果后续页面复杂度明显增加，再单独评估前后端分离，不能把它和持久层迁移放在同一阶段。

## 7. 安全与 Guardrails

- Data：SQL parser、单语句、只读、白名单、敏感字段、LIMIT、二次校验、人工确认。
- Knowledge：召回阈值、引用完整性、无依据拒答、敏感文本处理。
- Support：高风险转人工、引用校验、禁止承诺、确认 token、不自动发送。
- Report：事实/建议分离、sourceId 和数字一致性。
- Resume：受保护属性移除、证据绑定、不评分排名、不自动决策。

持久层条件更新不能替代任何业务 Guardrail 或对象访问策略。

## 8. 测试策略

### 单元测试

- Guardrails 和脱敏器。
- 业务状态流转。
- Prompt 上下文和结构化输出验证。
- Repository 之外的业务 service。

### 上下文测试

- 每个 `@AutoConfiguration` 使用 `ApplicationContextRunner`。
- enabled/disabled 开关。
- 模型和数据库 Bean 缺失时的降级。

### 数据库集成测试

- Testcontainers PostgreSQL + pgvector。
- Flyway 全量迁移。
- Spring JDBC CRUD、条件状态竞争和回滚。
- Spring JDBC 动态 SQL、PostgreSQL/MySQL metadata 和 pgvector 检索。
- 平台事务与外部只读查询边界。

### 构建测试

- Mockito 使用显式 agent 配置，避免未来 JDK 禁止动态自附加。
- Docker image build。
- 无模型配置启动 smoke test。

## 9. 模块依赖规则

```text
app
  -> business modules

business modules
  -> ai-core
  -> ai-guardrails
  -> common-web
  -> common-security（高风险对象模块）
  -> ai-tool-audit（只有真实使用时）
  -> Spring JDBC
```

- 不新增没有真实使用者的平台模块。
- 不允许 platform 依赖 business module。
- 业务模块直接依赖必须保持单向并通过窄接口。
- 依赖写在使用它的模块，不依赖宿主 app 的传递依赖完成编译。

## 10. 配置策略

无 API Key 的默认开发体验应为：

```yaml
spring:
  ai:
    model:
      chat: none
      embedding: none
```

启用模型时显式配置：

```text
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_MODEL_EMBEDDING=openai
SPRING_AI_OPENAI_API_KEY=...
SPRING_AI_OPENAI_BASE_URL=...
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=...
SPRING_AI_OPENAI_EMBEDDING_MODEL=...
```

生产环境凭据必须来自 secret manager 或运行环境，不写入仓库。

## 11. 不采用的技术

- JPA/Hibernate：当前大量动态 SQL、pgvector 和明确 SQL 审计不适合。
- WebFlux：没有端到端响应式需求。
- Redis：单机 demo 的确认 token 暂不需要。
- MQ/工作流平台：当前业务闭环不需要。
- 微服务：会显著增加交付和运行成本。
- 通用 AI agent 平台：与明确业务 Copilot 定位冲突。

完整实施顺序见 `docs/architecture-review-and-framework-plan.md`。
