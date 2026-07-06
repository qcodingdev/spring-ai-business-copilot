# 项目规划目录

本目录归纳当前 README、中文 README、`docs/project-plan.md`、`docs/module-plan.md` 和 `AGENTS.md` 中的产品、技术、边界与架构要求。

## 文档索引

- [01-v1-scope-and-implementation-spec.md](01-v1-scope-and-implementation-spec.md)：第一版 Data Copilot 范围、功能拆解、边界、接口、数据模型与可交给 AI 生成代码的实现规格。
- [02-technology-stack.md](02-technology-stack.md)：基于第一版功能闭环的技术栈推荐、选型理由、替代方案和不推荐项。
- [03-flow-and-architecture-diagrams.md](03-flow-and-architecture-diagrams.md)：总流程图、系统架构图、模块依赖图和各核心功能流程图。

## 第一版结论

第一版只实现 Data Copilot：数据库查询助手。

目标不是做通用聊天机器人，也不是提前做完整 AI 平台，而是完成一个可运行、可改造、可交付的业务闭环：

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
- 第一版不做多租户、复杂权限、商业 BI 看板、多模型平台、工作流编排，也不一次性实现所有业务模块。

