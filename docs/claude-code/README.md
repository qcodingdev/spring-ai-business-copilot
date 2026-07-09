# Claude Code 执行文档入口

本目录用于把 Spring AI Business Copilot 的阶段开发拆成 Claude Code 可以直接执行的任务说明。

## 使用方式

推荐执行顺序：

1. 先把 [v1-master-prompt.md](v1-master-prompt.md) 作为总约束交给 Claude Code。
2. 新项目从零开始时，按顺序执行 [v1-module-prompts.md](v1-module-prompts.md) 中的模块 prompt。
3. 当前仓库已经完成部分基础能力，后续开发优先执行 [v1-remaining-feature-prompts.md](v1-remaining-feature-prompts.md)。
4. Data Copilot 完成后，再执行 [v2-knowledge-copilot-prompts.md](v2-knowledge-copilot-prompts.md) 规划第二模块 Knowledge Copilot。
5. Knowledge Copilot 完成后，再执行 [v3-support-copilot-prompts.md](v3-support-copilot-prompts.md) 规划第三模块 Support Copilot。
6. 每完成一个阶段，用 [v1-review-checklist.md](v1-review-checklist.md) 或各阶段 prompt 内的 checklist 做自检。
7. 交给 Codex review 时，以本目录文档和 `AGENTS.md` 为评审基准。

## 关键结论

- 第一版只做 Data Copilot，不做其他业务模块功能。
- 第二模块优先规划 Knowledge Copilot，原因是痛点普遍、Demo 独立性强，并且能沉淀 RAG、引用和知识审计能力。**V2 已实现完成。**
- 第三模块优先规划 Support Copilot，原因是客服回复痛点强，并且可以复用 Knowledge Copilot 的知识检索和引用能力。**V3 已实现完成。**
- 包名统一使用 `dev.qcoding.businesscopilot` 开头。
- Maven `groupId` 统一使用 `dev.qcoding`。
- 技术栈使用 Java 21、Spring Boot 4.1.x、Spring AI 2.0.x、Maven 多模块。
- 所有 AI 输出进入业务动作前必须经过 guardrails。
- SQL 默认只读，查询前必须展示 SQL 并由用户确认。
- 前端第一屏就是 Data Copilot 工作台，不做营销页。
