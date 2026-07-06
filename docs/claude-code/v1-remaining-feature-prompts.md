# Data Copilot V1 剩余功能 Prompt 索引

本文档基于当前仓库状态整理剩余开发任务。实际给 Claude Code 执行时，请不要复制本索引全文，而是进入 `v1-remaining-prompts/`，每次只选择一个单功能 prompt。

执行原则：

1. 每次只执行一个 prompt 文件。
2. 不重复实现已经存在的模块能力，优先接入和补齐闭环。
3. 测试用例后续可以逐步补，不要求数量多；每个功能覆盖主流程和一两个关键失败场景即可。
4. 所有实现继续遵守 `AGENTS.md`、`docs/project-plan.md` 和 `docs/module-plan.md`。
5. 第一版只做 Data Copilot，不提前实现 Resume/Support/Knowledge/Report Copilot。

## 当前功能 Review

### 已有基础

- Maven 多模块骨架已存在：`app/business-copilot-app`、`platform/common-web`、`platform/ai-core`、`platform/ai-guardrails`、`platform/ai-tool-audit`、`modules/data-copilot`。
- `common-web` 已有统一响应、分页、业务异常和全局异常处理。
- `ai-core` 已有 AI 调用封装、prompt 模板加载、JSON 输出解析，以及 Data Copilot 的 SQL 生成和结果解释模板文件。
- `ai-guardrails` 已有 SQL 只读校验、禁止关键字、白名单、limit、敏感字段策略和结果脱敏能力。
- `ai-tool-audit` 已有审计领域对象、服务和 JDBC repository。
- `data-copilot` 已有 schema 上下文管理和自然语言转 SQL 生成服务。
- 已有单元测试覆盖一部分公共响应、AI core、guardrails、audit、schema 能力。

### 主要缺口

- 还没有 SQL 候选确认 token 机制，执行接口还无法做到“只执行服务端保存的 SQL”。
- 还没有只读 SQL 执行器、结果表格 DTO、执行前二次 guardrails、防御式脱敏。
- 还没有结果解释服务接入业务执行流程。
- 还没有 Data Copilot REST API。
- 还没有 Thymeleaf 前端工作台。
- 还没有 Flyway 迁移、示例业务数据、审计表 DDL。
- 还没有 Docker Compose 一键启动。
- README 和模块文档还停留在规划入口，没有运行说明和 Data Copilot 独立说明。
- `mvn test` 当前在 JDK 24 环境下卡在 Mockito inline self-attach 初始化，属于测试运行环境/Mockito 配置问题，需要后续单独修正。

## 单功能 Prompt 文件

建议按顺序执行：

1. [01-sql-confirmation.md](v1-remaining-prompts/01-sql-confirmation.md)
2. [02-readonly-query-execution.md](v1-remaining-prompts/02-readonly-query-execution.md)
3. [03-result-explanation.md](v1-remaining-prompts/03-result-explanation.md)
4. [04-data-copilot-rest-api.md](v1-remaining-prompts/04-data-copilot-rest-api.md)
5. [05-flyway-sample-data.md](v1-remaining-prompts/05-flyway-sample-data.md)
6. [06-docker-compose.md](v1-remaining-prompts/06-docker-compose.md)
7. [07-thymeleaf-workbench.md](v1-remaining-prompts/07-thymeleaf-workbench.md)
8. [08-audit-lifecycle.md](v1-remaining-prompts/08-audit-lifecycle.md)
9. [09-readme-and-docs.md](v1-remaining-prompts/09-readme-and-docs.md)
10. [10-build-and-test-closeout.md](v1-remaining-prompts/10-build-and-test-closeout.md)
