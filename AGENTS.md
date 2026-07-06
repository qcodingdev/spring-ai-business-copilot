# AGENTS.md

## 项目定位

本项目是 Spring AI Business Copilot，一个面向个人开发者、中小团队和企业内部系统的 Java AI 业务智能助手套件。

项目目标不是做框架，而是做可运行、可改造、可交付的 Spring AI 业务项目。

第一版只实现 Data Copilot：数据库查询助手。架构必须预留 Resume Copilot、Support Copilot、Knowledge Copilot 和 Report Copilot。

## 产品规则

- 必须服务真实业务场景，不做泛泛聊天机器人。
- 每个模块都要能独立解释业务价值。
- 第一版优先做完整闭环，不追求模块数量。
- 所有公共能力必须来自业务模块沉淀，不提前过度抽象。
- README 面向中英文用户，公开文档需要双语入口。
- 内部规划文档使用中文。

## 第一版范围

第一版只做 Data Copilot。

必须实现：

- Spring Boot 后端
- Spring AI 调用
- 示例业务数据库
- schema 上下文管理
- 自然语言转 SQL
- SQL 只读校验
- SQL 执行前确认
- 查询结果表格
- AI 结果解释
- 查询审计日志
- Docker Compose 一键启动

不要优先做：

- 多租户
- 复杂权限系统
- 商业 BI 看板
- 多模型平台
- 工作流编排平台
- 一次性实现所有业务模块

## 架构规则

推荐模块：

- `app`：启动应用和 Web/API 入口
- `platform/ai-core`：模型调用、prompt、通用 AI 能力
- `platform/ai-guardrails`：SQL 安全、敏感信息、业务边界
- `platform/ai-tool-audit`：工具调用和查询审计
- `platform/common-web`：通用响应、异常、分页
- `modules/data-copilot`：数据库查询助手
- `modules/resume-copilot`：预留简历模块
- `modules/support-copilot`：预留客服模块

规则：

- 业务模块不能直接散落在通用层。
- 通用层必须由至少一个业务模块真实使用。
- SQL 安全逻辑必须可测试。
- prompt 模板必须集中管理，不能散落在 service 代码中。
- 所有 AI 输出进入业务动作前必须经过 guardrails。

## 安全规则

- Data Copilot 默认只读。
- 禁止执行 `insert`、`update`、`delete`、`drop`、`alter`、`truncate`、`create`、`grant`、`revoke`。
- 查询前必须展示 SQL。
- 查询必须记录审计日志。
- 敏感字段默认脱敏，例如 phone、email、id_card、password、token、secret。
- 示例数据不能包含真实个人信息。

## 文档规则

- `README.md` 英文入口。
- `README.zh-CN.md` 中文入口。
- 规划写在 `docs/project-plan.md`。
- 模块规划写在 `docs/module-plan.md`。
- 每个业务模块后续都要有独立说明文档。

