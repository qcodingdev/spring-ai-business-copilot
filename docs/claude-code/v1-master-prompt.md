# Claude Code 总 Prompt：Spring AI Business Copilot V1

## 1. 你的角色

你是 Claude Code，负责在当前仓库实现 Spring AI Business Copilot 第一版。

你只负责编码实现，不重新发明产品方向。所有实现必须遵守：

- `AGENTS.md`
- `docs/project-plan.md`
- `docs/module-plan.md`
- `docs/project-planning/01-v1-scope-and-implementation-spec.md`
- `docs/project-planning/02-technology-stack.md`
- `docs/project-planning/03-flow-and-architecture-diagrams.md`
- 本目录所有 Claude Code 执行文档

## 2. 项目目标

第一版只实现 Data Copilot：数据库查询助手。

用户可以在页面输入自然语言业务问题，系统生成只读 SQL，展示 SQL 和安全校验结果，用户确认后执行查询，返回表格结果和 AI 业务解释，并记录审计日志。

## 3. 绝对边界

不要实现：

- 多租户。
- 登录注册。
- 复杂权限系统。
- 商业 BI 看板。
- 多模型管理平台。
- 工作流编排平台。
- Resume Copilot 业务功能。
- Support Copilot 业务功能。
- Knowledge Copilot 业务功能。
- Report Copilot 业务功能。
- 生产数据库连接管理。
- 自动执行模型生成的 SQL。

必须实现：

- Spring Boot 后端。
- Spring AI 调用。
- PostgreSQL 示例业务数据库。
- Flyway 初始化表结构和示例数据。
- Schema 上下文管理。
- 自然语言转 SQL。
- SQL 只读校验。
- SQL 执行前确认。
- 查询结果表格。
- 查询结果敏感字段脱敏。
- AI 结果解释。
- 查询审计日志。
- Docker Compose 一键启动。
- 基础前端工作台。
- 核心单元测试。

## 4. 技术框架

使用当前项目骨架：

- Java 21。
- Maven 多模块。
- Spring Boot 4.1.x。
- Spring AI 2.0.x。
- Spring Web MVC。
- Thymeleaf。
- 少量原生 JavaScript。
- Spring JDBC。
- PostgreSQL。
- Flyway。
- JSQLParser。
- JUnit 5。
- AssertJ。
- Mockito。
- Testcontainers PostgreSQL。

不要改成：

- Gradle。
- WebFlux。
- React/Vue SPA。
- MyBatis Plus。
- JPA 执行模型生成 SQL。
- LangChain4j。
- 自建多模型平台。

## 5. 包名和模块名

Maven `groupId` 必须是：

```text
dev.qcoding
```

Java 包名必须以：

```text
dev.qcoding.businesscopilot
```

开头。

建议包结构：

```text
dev.qcoding.businesscopilot
dev.qcoding.businesscopilot.app
dev.qcoding.businesscopilot.commonweb
dev.qcoding.businesscopilot.aicore
dev.qcoding.businesscopilot.guardrails
dev.qcoding.businesscopilot.audit
dev.qcoding.businesscopilot.datacopilot
```

不要使用：

- `io.github...`
- `com.example...`
- `demo...`
- 无意义缩写包名

## 6. Maven 模块架构

保留并完善以下模块：

```text
app/business-copilot-app
platform/ai-core
platform/ai-guardrails
platform/ai-tool-audit
platform/common-web
modules/data-copilot
```

模块职责：

- `app/business-copilot-app`：启动类、配置装配、静态资源、页面模板、Flyway 脚本、Docker 运行入口。
- `platform/common-web`：统一响应、错误码、异常处理、分页对象。
- `platform/ai-core`：Spring AI ChatClient 封装、prompt 模板加载、模型 JSON 输出解析。
- `platform/ai-guardrails`：SQL 只读校验、白名单校验、敏感字段策略、结果脱敏。
- `platform/ai-tool-audit`：审计模型、审计服务、审计持久化接口。
- `modules/data-copilot`：Data Copilot 业务流程编排、API、schema 上下文、SQL 候选、查询执行、结果解释。

依赖方向：

```text
app -> data-copilot
data-copilot -> ai-core
data-copilot -> ai-guardrails
data-copilot -> ai-tool-audit
data-copilot -> common-web
ai-tool-audit -> common-web 可选
common-web 不依赖业务模块
platform 模块之间不要形成循环依赖
```

## 7. 注释要求

适当增加中文、英文注释，但不要堆砌。

必须添加注释的位置：

- SQL guardrails 的核心判断。
- 敏感字段脱敏策略。
- 执行前确认 token 的安全原因。
- Prompt 模板中的模型约束。
- 审计日志不记录敏感结果的原因。

注释风格：

- Java 类和关键 public 方法使用英文 Javadoc，必要时补一行中文解释。
- 复杂业务规则附近使用简短中文注释。
- Prompt 文件中可以使用中英文混合说明。
- 不要写“这行代码设置变量”这类无价值注释。

示例：

```java
/**
 * Validates that generated SQL is safe to preview and execute after user confirmation.
 *
 * <p>校验模型生成的 SQL 是否满足只读、单语句和白名单约束。</p>
 */
```

## 8. 安全要求

SQL 安全：

- 只允许单条 `SELECT` 或 `WITH ... SELECT`。
- 禁止多语句。
- 禁止注释中夹带危险语句。
- 禁止 `insert`、`update`、`delete`、`drop`、`alter`、`truncate`、`create`、`grant`、`revoke`、`merge`、`call`、`execute`。
- Parser 失败默认拒绝执行。
- 白名单外表拒绝。
- 高敏字段直接查询拒绝。
- 默认必须有行数限制，聚合查询可例外。
- 查询必须设置 timeout 和 max rows。

执行确认：

- 生成 SQL 后只返回候选。
- 前端必须展示 SQL。
- 用户确认后才执行。
- 执行接口不能信任客户端传回的 SQL。
- 执行接口必须读取服务端保存的 SQL 候选。
- SQL 候选必须设置过期时间。

数据安全：

- `phone`、`email` 默认脱敏。
- `id_card`、`password`、`token`、`secret` 默认阻断或全遮蔽。
- 审计日志不记录完整敏感查询结果。
- 示例数据不能包含真实个人信息。

## 9. AI Prompt 要求

Prompt 模板必须集中放在 resources 中，不要写在 service 代码里。

建议路径：

```text
platform/ai-core/src/main/resources/prompts/data-copilot/sql-generation.st
platform/ai-core/src/main/resources/prompts/data-copilot/result-explanation.st
```

模型输出必须结构化。

SQL 生成输出示例：

```json
{
  "sql": "select ... limit 100",
  "summary": "该 SQL 用于统计上个月销售额。",
  "assumptions": ["按 orders.created_at 判断月份"],
  "warnings": []
}
```

结果解释输出可以是自然语言，但必须只基于查询结果，不得编造。

## 10. 前端要求

第一屏就是 Data Copilot 工作台。

页面必须包含：

- 顶部应用标题。
- 自然语言问题输入框。
- 生成 SQL 按钮。
- SQL 候选展示区域。
- Guardrails 校验结果。
- 确认执行按钮。
- 查询结果表格。
- AI 解释区域。
- 错误提示区域。
- 最近审计记录入口或简表。

交互要求：

- 生成 SQL 时显示 loading 状态。
- 校验失败时禁用确认按钮。
- 执行查询时显示 loading 状态。
- 空结果有友好提示。
- 错误信息明确但不泄露内部堆栈。
- 页面可在桌面和手机宽度下正常使用。

视觉要求：

- 做工具型工作台，不做营销落地页。
- 信息密度适中，适合反复查询。
- 表格可横向滚动，不能撑破布局。
- SQL 使用等宽字体。
- 危险和阻断状态要清晰。
- 不使用大面积炫彩渐变。

## 11. 测试要求

必须测试：

- SQL 只读校验通过普通 `select`。
- SQL 只读校验通过 `with ... select`。
- 禁止关键字全部拒绝。
- 多语句拒绝。
- Parser 失败拒绝。
- 白名单外表拒绝。
- 高敏字段拒绝。
- phone/email 脱敏。
- SQL 候选过期不可执行。
- 成功和失败审计都写入。

建议测试：

- Schema 上下文生成。
- Prompt 模板渲染。
- Query timeout 配置。
- API 参数校验。
- 前端页面基本渲染。

## 12. 交付标准

完成后必须满足：

```bash
mvn -q -DskipTests compile
mvn test
mvn -q -DskipTests package
```

如果集成测试依赖 Docker，必须在文档中说明如何运行。

不要提交真实 API Key。

