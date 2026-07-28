# 架构决策记录

本目录只记录已经决定、会影响代码结构或安全边界的决策。候选方案和未承诺需求继续保留在规划文档中。

| ADR | 状态 | 主题 |
|---|---|---|
| [0001](0001-platform-and-business-query-datasources.md) | 已采纳 | 平台库与业务查询库双 DataSource 边界 |
| [0002](0002-authentication-roles-and-audit-context.md) | 已采纳 | 基础认证角色与审计操作者上下文 |
| [0003](0003-object-ownership-and-confirmation-token-lifecycle.md) | 已采纳 | 对象所有权与确认 token 生命周期 |
| [0004](0004-data-query-dialect-boundary.md) | 已采纳 | Data PostgreSQL/MySQL 查询方言边界 |
| [0005](0005-audit-v2-and-failure-policy.md) | 已采纳 | 审计 v2、保留期与失败策略 |
| [0006](0006-ai-call-observability-and-resilience.md) | 已采纳 | AI 调用链、低基数指标与故障隔离 |
