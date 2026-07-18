# 项目架构评审与框架改造计划

> 评审日期：2026-07-10
>
> 评审范围：根 Maven 工程、app、platform、Data/Knowledge/Support/Report/Resume Copilot、测试、README、内部规划文档和 Docker 启动链路。最近状态校正：2026-07-16。  
> 文档定位：保留为历史框架改造记录；当前审核结论、风险优先级和下一阶段路线以 `current-project-audit-2026-07-16.md`、`project-plan.md` 和 `upgrade-roadmap.md` 为准。
> 失效决策：本文中的 MyBatis-Plus 候选方案未进入现行架构；v1.2 已统一为模块内显式 Spring JDBC Repository，并删除 Mapper 扫描依赖。

## 当前复审结论

- 整体模块边界合理，没有演变成通用 AI 框架：业务状态、guardrail 和审计模型仍归各 Copilot 所有。
- Spring AI 2.0、Spring Boot 4.1 和 Jackson 3 组合可用；Prompt 集中管理，结构化输出进入业务动作前有业务校验。
- MyBatis-Plus starter 只在 app 装配，Resume 仅使用 core API 表达稳定聚合 CRUD；动态 SQL、pgvector 和批量写入继续使用 JDBC，未强行统一持久层。
- app Maven plugin 已绑定 `repackage`，构建产物是可执行 jar，Dockerfile 的 `java -jar` 链路成立。
- README、五个模块说明、真实工作台 GIF、虚构示例和 Docker Compose 已形成开源项目入口。
- 真实 PostgreSQL/pgvector Testcontainers、Flyway 升级和无模型 Compose smoke 已通过；当前剩余重点是 Data schema/function/LIMIT 安全、数据库最小权限、对象级授权、独立宿主自动配置、审计准确性、远端 CI 和真实模型 smoke。

## 1. 结论

当前项目的业务模块化方向正确，不需要推倒重写。

建议保留：

- `app -> modules -> platform` 的总体依赖方向。
- 每个 Copilot 独立业务闭环、Guardrails、人工确认和审计。
- Prompt 集中在 `platform/ai-core/src/main/resources/prompts`。
- PostgreSQL、Flyway、Thymeleaf 和原生 JavaScript。
- Data Copilot 的双重 SQL Guardrails 和 JDBC 只读执行。

需要改造：

- 引入 MyBatis-Plus 处理稳定业务表 CRUD，但不能替代所有 JDBC。
- 将 Spring AI 2.0 的使用方式从“文本生成 + 自定义 Jackson 2 正则解析”升级为 schema-validated structured output（已完成）。
- 清理应用代码中的 Jackson 2 解析路径（已完成）；OpenAI SDK 保留其传递 Jackson 2 兼容依赖。
- 修正不完整的自动配置和无效的模块 `enabled` 开关。
- 清理未使用依赖和过期 Maven description（本次评审已完成第一轮）。
- 增加 PostgreSQL/Testcontainers 集成测试，覆盖 Flyway、MyBatis-Plus、pgvector 和事务。
- 明确审计写入失败时是 fail-open 还是 fail-closed。

该顺序是 2026-07-10 时的历史建议。Report 和 Resume 当前都已完成第一轮闭环；现阶段不再新增模块，执行顺序改为 v1.1 发布收口、v1.2 可信执行与 Data 升级，再纵向升级 Knowledge/Support/Report/Resume。

---

## 2. 当前技术基线

| 类别 | 当前状态 | 评审 |
|---|---|---|
| Java | 21 | 合理，保留 |
| Spring Boot | 4.1.0 | 合理，与 Spring AI 2.0 匹配 |
| Spring AI | 2.0.0 | 已经使用正式版，无需升级大版本 |
| 构建 | Maven 多模块 | 合理，保留 |
| 数据库 | PostgreSQL 16 + pgvector | 合理，覆盖结构化数据和向量检索 |
| 迁移 | Flyway | 合理，继续作为唯一 DDL 管理工具 |
| 持久层 | Spring JDBC | 动态 SQL 合理，普通 CRUD 重复较多 |
| JSON | 应用代码使用 Jackson 3；OpenAI SDK 传递 Jackson 2 兼容依赖 | 不在业务模块直接使用 Jackson 2 |
| Web | Spring MVC + Thymeleaf + 原生 JS | 与项目规模匹配 |
| 测试 | JUnit 5、AssertJ、Mockito | 单元测试较好，真实数据库集成测试不足 |

官方兼容性结论：

- Spring AI 2.0 面向 Spring Boot 4.0/4.1 和 Spring Framework 7。
- MyBatis-Plus 从 3.5.13 起支持 Spring Boot 4；规划采用 3.5.16 的 `mybatis-plus-spring-boot4-starter`。
- MyBatis-Plus 官方要求不要同时引入原生 MyBatis starter，避免版本冲突。

---

## 3. 架构评审

### 3.1 合理部分

#### 业务模块边界

Data、Knowledge、Support、Report、Resume Copilot 都有独立的输入、业务流程、安全边界和审计模型，没有把业务逻辑堆到通用平台层。

#### AI 输出安全

- Data Copilot 在生成和执行前分别校验 SQL。
- Knowledge Copilot 强制引用并在无依据时拒答。
- Support Copilot 只生成草稿，高风险转人工，不自动发送。
- Prompt 已集中管理。

#### 技术复杂度

项目没有过早引入网关、服务发现、消息队列、Redis、多租户、复杂权限和工作流平台，符合可运行示例项目定位。

### 3.2 需要优先修复的问题

#### 已收口：应用代码 Jackson 2/3 双栈

`ai-core` 的 Jackson 2 databind 和自定义解析器已删除；结构化输出现在由 Spring AI `ChatClient.entity(...).validateSchema()` 处理。`ai-tool-audit` 的未使用 Jackson 2 依赖也已删除。Spring AI OpenAI starter 的 `openai-java-core` 仍传递 Jackson 2 databind，这是 provider SDK 的兼容依赖，不由业务代码直接使用。

影响：

- 两套 ObjectMapper 配置不一致。
- 模型结构化输出与 Web JSON 行为可能不同。
- 增加依赖冲突和后续升级成本。

改造方向：

- 优先使用 Spring AI 2.0 `ChatClient.call().entity(...)`（已完成）。
- 对兼容模型使用 prompt-based structured output + `validateSchema()`（已完成）。
- 只有确实需要容忍 Markdown fence 时才新增自定义 converter。

#### P1：自动配置不完整（仍未完全收口）

多个模块把类写入 `AutoConfiguration.imports`，但使用普通 `@Configuration`，且部分 service/controller 依赖主应用的包扫描才能注册。

当前主应用位于共同根包下，因此问题被掩盖；Data Controller 已显式装配，但 Knowledge/Support/Report/Resume Controller 和 Resume Mapper 仍可能依赖宿主应用包扫描。模块被其他 Spring Boot 应用复用时可能缺 Bean。

改造方向：

- 使用 `@AutoConfiguration`。
- 显式 `@Bean` 或受控 `@Import`，不依赖宿主应用扫描业务模块。
- 增加 `@ConditionalOnClass`、`@ConditionalOnBean`、`@ConditionalOnMissingBean`。
- `enabled` 属性使用 `@ConditionalOnProperty` 真正控制模块装配。
- 使用 `ApplicationContextRunner` 验证独立自动配置。

#### 已修复：模型开关默认值不一致

`application.yml`、Docker Compose、`.env.example` 和 README 对 chat/embedding 默认状态描述不一致。无 API Key 启动路径不够确定。

本次评审已完成：

- 开发、Docker 和 `.env.example` 默认都设置 chat/embedding 为 `none`。
- 启用 OpenAI 兼容模型时同时显式配置 provider、base URL、模型和 API Key。
- 启动测试覆盖“无模型可启动”和“模型配置错误提示清晰”。

#### P1：审计语义冲突

文档要求所有查询必须审计，但 `AuditService.record` 当前吞掉持久化异常并继续业务流程。

建议：

- Data Copilot 的 SQL 执行确认和执行结果采用 fail-closed：关键审计无法写入时不继续执行或明确返回失败。
- 纯展示型 AI 解释可 fail-open。
- Knowledge/Support 根据业务动作风险分别定义策略，不能所有场景共用“永不影响主流程”。

### 3.3 中优先级问题

- Knowledge、Support 的未使用 `ai-tool-audit` 依赖已删除。
- Data、Knowledge 的过期 POM description 已修正。
- `common-web` 同时承担通用错误模型和 Web 适配；现阶段可接受，模块继续增长后再考虑拆分 `common-kernel`，现在不要拆。
- Support 到 Knowledge 是明确单向依赖，可以接受；后续优先在 app 层装配 adapter，避免继续扩大业务模块直接依赖面。
- Mockito 已通过 Surefire 显式加载 agent，避免运行时自附加弃用警告。
- 2026-07-16 本地基线为 256 tests、0 failures/errors/skips；真实 PostgreSQL、Flyway 和 pgvector 核心集成已通过，但五个模块的 Repository/状态竞争、MySQL 和 AI 质量评测仍不足。

---

## 4. MyBatis-Plus 采用决策

### 4.1 结论

采用 MyBatis-Plus，但使用混合持久层，不做全量替换。

目标版本：

```text
com.baomidou:mybatis-plus-bom:3.5.16
com.baomidou:mybatis-plus-spring-boot4-starter:3.5.16
```

规则：

- 根 POM 用 BOM 管理版本。
- app 引入 Boot 4 starter。
- 业务模块只引入编译所需的 MyBatis-Plus core/extension，不重复引入其他 MyBatis starter。
- 不使用 ActiveRecord。
- 不强制业务 service 继承 `ServiceImpl`。
- 保留现有 Repository 接口，Mapper 作为基础设施实现细节。
- Flyway 继续负责建表，不启用 ORM 自动建表。
- 第一阶段不启用分页、逻辑删除、租户、数据权限等拦截器。

### 4.2 迁移矩阵

| 当前组件 | 目标 | 原因 |
|---|---|---|
| `JdbcKnowledgeDocumentRepository` | MyBatis-Plus | 标准 CRUD 和状态更新 |
| `JdbcKnowledgeChunkRepository` | MyBatis-Plus + 必要自定义 SQL | 批量写入、按 document 查询/删除 |
| `JdbcKnowledgeQaAuditRepository` | MyBatis-Plus | 标准插入和分页查询 |
| `JdbcSupportTicketRepository` | MyBatis-Plus | 标准插入和状态更新 |
| `JdbcSupportReplyDraftRepository` | MyBatis-Plus | 标准插入、查询和状态更新 |
| `JdbcSupportAuditRepository` | MyBatis-Plus | 标准插入和分页查询 |
| `JdbcQueryAuditRepository` | MyBatis-Plus | 稳定审计表 CRUD |
| `JdbcKnowledgeEmbeddingRepository` | 保留 JDBC | pgvector 类型、距离运算和定制 SQL更清晰 |
| `JdbcSchemaMetadataRepository` | 保留 JDBC | information_schema 元数据查询不是实体 CRUD |
| `JdbcReadOnlyQueryExecutor` | 必须保留 JDBC | 执行运行时生成的动态只读 SQL |

### 4.3 JSQLParser 冲突控制

Data Copilot 当前直接使用 JSQLParser 4.9 做安全校验。MyBatis-Plus 的插件解析器从 3.5.9 起是可选依赖。

迁移初期：

- 只引入 MyBatis-Plus starter/core，不引入分页等需要 parser 的插件。
- 保持 Guardrails 的 JSQLParser 4.9 不变。
- 如果后续需要 MyBatis-Plus parser 插件，单独做兼容性验证，再统一 parser 版本。

---

## 5. Spring AI 2.0 改造方向

项目已经是 Spring AI 2.0.0，重点是使用方式升级。

### 保留

- `ChatClient.Builder` 注入。
- `EmbeddingModel` 抽象。
- resource prompt 模板。
- 模型不可用时的业务降级。

### 改造

- `AiChatService.generateJson` 改为基于 `ChatClient.entity(type, spec -> spec.validateSchema())`。
- 将 system instruction 与用户数据拆分，避免把整段 prompt 都放到 user message。
- 根据 provider 能力选择是否启用 native structured output，不能全局假设兼容。
- 获取 `ChatResponse` 元数据，用于审计 token usage、model 和 finish reason。
- 使用 Spring AI observation/Micrometer，避免自建重复 tracing 平台。
- 将 `AiModelProperties` 拆分 chat/embedding 模型名，避免 embedding 审计记录 chat model 名称。

---

## 6. 目标依赖结构

```text
business-copilot-app
  -> data-copilot
  -> knowledge-copilot
  -> support-copilot
  -> mybatis-plus-spring-boot4-starter

data-copilot
  -> ai-core
  -> ai-guardrails
  -> ai-tool-audit
  -> common-web
  -> Spring JDBC (dynamic read-only SQL)

knowledge-copilot
  -> ai-core
  -> ai-guardrails
  -> common-web
  -> MyBatis-Plus core (CRUD)
  -> Spring JDBC (pgvector only)

support-copilot
  -> ai-core
  -> ai-guardrails
  -> common-web
  -> knowledge-copilot adapter
  -> MyBatis-Plus core (CRUD)
```

暂不新增 `platform/persistence`。当前共享内容只有依赖版本和少量配置，新建平台模块会增加抽象层而没有足够业务价值。

---

## 7. 分阶段实施计划

### Phase 0：基线和配置修正

- 固化完整测试命令和 31 个测试 suite 基线（已完成）。
- 统一 chat/embedding 无 Key 默认关闭策略（已完成）。
- 修复 README、本地启动命令和模块说明（已完成）。
- 清理未使用依赖和过期 POM description（第一轮已完成）。
- 为 Mockito agent 制定显式 Surefire 配置。

验收：无 Key 可启动基础设施；全量测试通过；依赖树无意外变化。

### Phase 1：Spring AI 2.0 原生化与自动配置修复（部分完成）

- 迁移 Jackson 3。
- 使用 `ChatClient.entity()` 和 schema validation。
- 将现有 AutoConfiguration 迁移为显式 `@AutoConfiguration`（已完成类级迁移，Controller/Mapper 独立宿主装配待完成）。
- 让 Knowledge/Support `enabled` 开关真正生效。
- 增加 AI Core、Audit、Knowledge、Support 的独立 auto-configuration 测试。

验收：无应用直接 Jackson 2 databind 依赖或解析代码；AI/Audit 可独立装配；Knowledge/Support 可关闭；模型结构化输出错误可控。五个业务模块的外部最小宿主装配测试尚未完成。

### Phase 2：MyBatis-Plus 基础与 Knowledge 试点

- 根 POM 引入 MyBatis-Plus BOM 3.5.16。
- app 引入 Boot 4 starter。
- 先迁移 Knowledge document/chunk/audit CRUD。
- embedding/vector repository 保留 JDBC。
- 增加 PostgreSQL Testcontainers 测试。

验收：Knowledge API 行为不变；事务回滚、批量 chunk、分页和 pgvector 检索通过。

### Phase 3：Support 和 Data 审计迁移

- 迁移 Support ticket/draft/audit CRUD。
- 迁移 query audit CRUD。
- 不修改 Data 动态 SQL executor 和 schema metadata JDBC。
- 明确并测试审计 fail-open/fail-closed 策略。

验收：状态流转、确认 token、审计关联和 Data 双重校验全部通过。

### Phase 4：架构收口

- 清理剩余无效 JDBC RowMapper 和重复 SQL。
- 检查模块依赖和自动配置条件。
- 更新架构图、模块 README 和扩展指南。
- 运行全量单元、上下文、数据库集成和 Docker smoke test。

历史验收：当时计划通过后再开始 V4。当前 V4/V5 已完成第一轮闭环，新的收口标准见当前审核文档。

---

## 8. 防止过度设计

本次改造明确不做：

- 不拆微服务。
- 不新增网关、注册中心、消息队列和分布式事务。
- 不建立通用 Repository 框架或 BaseEntity 继承体系。
- 不使用 MyBatis-Plus ActiveRecord、自动代码生成和全套拦截器。
- 不把 pgvector 查询强行包装成通用 ORM。
- 不建立多模型路由平台。
- Report/Resume 的表结构只服务各自已实现闭环，不为未来 ATS、发布平台或工作流系统提前抽象。

原则：每一步都必须由现有模块的真实重复或风险驱动。

---

## 9. 最终验收清单

- Spring Boot 4.1.0、Spring AI 2.0.0、MyBatis-Plus 3.5.16 版本受 BOM 管理。
- 业务模块不直接声明或使用 Jackson 2 databind；provider SDK 的传递兼容依赖需要在依赖树中说明。
- MyBatis-Plus 只负责稳定 CRUD，动态 SQL、元数据和 pgvector 保留 JDBC。
- 每个模块的 enabled 开关有效。
- 每个 auto-configuration 可独立测试。
- Prompt 不散落，结构化输出有 schema 校验和业务 guardrails。
- 审计失败策略符合业务风险，不再与文档矛盾。
- PostgreSQL、Flyway、MyBatis-Plus 和 pgvector 有真实集成测试。
- 根 README 和各 Maven 模块 README 与实际状态一致。
- V4/V5 Prompt 使用更新后的框架规则。
