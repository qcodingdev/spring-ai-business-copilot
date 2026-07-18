# ADR-0003：对象所有权与确认 token 生命周期

- 状态：已采纳
- 日期：2026-07-16

## 背景

Data、Support、Report、Resume 都有高风险候选、草稿、确认或复核动作。仅按 URL 和角色授权无法阻止一个已认证用户处理另一个用户的对象；明文 token、内存 candidate 和非条件状态更新也无法可靠处理重启、重放和并发竞争。

## 决策

1. 创建窄 `platform/common-security`，提供当前 actor、角色、对象动作策略和 token 摘要辅助；Spring Security 适配留在 app。
2. ADMIN 可处理全部对象；OPERATOR 只处理自己创建的对象；REVIEWER 只处理明确进入复核队列的对象。
3. Data candidate 必须落入平台 PostgreSQL，不保留内存或 Redis 实现。
4. 明文 token 只在创建响应中返回一次；数据库只保存 SHA-256 摘要。
5. 确认、执行、取消、导出和复核同时校验 actor、角色、对象、token、状态和有效期。
6. 所有状态变化使用数据库条件更新；合法对象的过期、重放和状态竞争返回 409。
7. 对象不可见或跨 owner 访问返回安全 404，避免泄露对象存在性。
8. Data、Support、Report、Resume 保留各自状态表，不建立万能 confirmation 或工作流表。

## 后果

- Flyway V11 创建 `data_sql_candidates`，并升级 Support、Report、Resume 的 owner、token 和状态字段。
- v1.1 尚未消费的明文 token 在升级时失效。
- 每个高风险动作审计必须区分 creatorActorId 和 actionActorId。
- API 客户端必须把 token 当作一次性凭据，不能依赖后续查询回显。

## 验证

- owner、跨 owner、ADMIN、REVIEWER queue 矩阵。
- 错误 token、过期、重放、应用重启。
- 并发确认和确认/取消竞争只有一个成功。
- V10→V11 历史升级保留业务数据并失效旧 token。
