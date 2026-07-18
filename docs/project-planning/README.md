# 项目规划目录

本目录归纳当前 README、中文 README、项目规划、技术基线和架构演进要求。

## 文档索引

- [01-v1-scope-and-implementation-spec.md](01-v1-scope-and-implementation-spec.md)：V1 Data Copilot 的历史实现规格，用于理解初始边界，不代表当前全部模块状态。
- [02-technology-stack.md](02-technology-stack.md)：当前技术基线、Spring AI 2.0、显式 Spring JDBC Repository 和测试策略。
- [03-flow-and-architecture-diagrams.md](03-flow-and-architecture-diagrams.md)：当前/目标架构、模块依赖、双 DataSource 和各 Copilot 流程。
- [../architecture-review-and-framework-plan.md](../architecture-review-and-framework-plan.md)：2026-07 全项目评审、问题清单、迁移矩阵和分阶段改造计划。

## 当前结论

当前已经实现 Data、Knowledge、Support、Report、Resume 五个 Copilot；本目录保留 V1 阶段设计作为历史基线，当前状态以 `docs/project-plan.md` 和各模块文档为准。

项目仍然不是通用聊天机器人或 AI 平台，每个模块都必须形成可运行、可改造、可交付的业务闭环。

1. 用户输入业务问题。
2. 系统读取并组织可访问 schema 上下文。
3. Spring AI 调用模型生成只读 SQL。
4. SQL 进入 guardrails 做只读、安全、表字段、限制条件校验。
5. 前端展示 SQL，用户确认后才执行。
6. 后端执行只读查询并返回表格结果。
7. 敏感字段脱敏。
8. AI 用业务语言解释结果。
9. 全链路写入查询审计日志。
10. Docker Compose 一键启动应用、数据库和示例数据。

## 必须坚持的边界

- 默认只读，不允许任何写库、改表、授权、DDL、DML 操作。
- 所有 AI 输出进入 SQL 执行前必须经过 guardrails。
- prompt 模板集中管理，不能散落在 service 代码中。
- 查询前必须展示 SQL，不能自动执行模型生成的 SQL。
- 查询必须记录审计日志。
- 示例数据不能包含真实个人信息。
- 不做多租户、复杂权限、商业 BI 看板、多模型平台和工作流编排。
- 模块内显式 Spring JDBC Repository 处理 CRUD、条件状态更新、动态 SQL、metadata 和 pgvector；不同风险使用不同窄边界。
- Spring AI 结构化输出不能替代业务 Guardrails。
