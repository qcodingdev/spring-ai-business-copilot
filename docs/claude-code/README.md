# Claude Code 执行文档入口

本目录用于把 Spring AI Business Copilot 的阶段开发拆成 Claude Code 可以直接执行的任务说明。

## 使用方式

推荐执行顺序：

1. 当前仓库后续开发优先阅读 `docs/v1.2-trusted-execution-plan.md`，再执行 [v1.2-trust-and-data-upgrade-prompts.md](v1.2-trust-and-data-upgrade-prompts.md)。
2. 执行前同时阅读 `docs/current-project-audit-2026-07-16.md`、`docs/upgrade-roadmap.md` 和 `AGENTS.md`。
3. 每个 Prompt 都按顺序执行，前一切片未通过时不要扩大到下一切片。
4. [v1-master-prompt.md](v1-master-prompt.md)、[v1-module-prompts.md](v1-module-prompts.md) 和 v1 remaining prompts 是 Data 首版历史实现资料。
5. [v2-knowledge-copilot-prompts.md](v2-knowledge-copilot-prompts.md)、[v3-support-copilot-prompts.md](v3-support-copilot-prompts.md)、[v4-report-copilot-prompts.md](v4-report-copilot-prompts.md)、[v5-resume-copilot-prompts.md](v5-resume-copilot-prompts.md) 是五模块第一轮闭环的历史资料。
6. 交给 Codex review 时，以当前审核、升级路线和 `AGENTS.md` 为评审基准，不把历史 Prompt 当作当前范围。

## 关键结论

- Data、Knowledge、Support、Report、Resume 五个业务模块已经完成第一轮闭环。
- 当前不增加第六个模块；v1.1 Data 安全、Draft PR 和远端 CI 已完成，仅待五模块真实模型 smoke。
- v1.2 主线是对象级授权、可靠确认、真实模块自动配置、审计 v2 和 PostgreSQL/MySQL 业务查询目标。
- v1.3～v1.6 再分别纵向升级 Knowledge、Support、Report 和 Resume。
- 包名统一使用 `dev.qcoding.businesscopilot` 开头。
- Maven `groupId` 统一使用 `dev.qcoding`。
- 技术栈使用 Java 21、Spring Boot 4.1.x、Spring AI 2.0.x、Maven 多模块。
- 所有 AI 输出进入业务动作前必须经过 guardrails。
- SQL 默认只读，查询前必须展示 SQL 并由用户确认。
- 前端保持统一业务工作台，不扩展为营销站或通用平台。
