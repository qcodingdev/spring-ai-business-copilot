# ADR-0005：审计 v2、保留期与失败策略

- 状态：已采纳
- 日期：2026-07-16

## 背景

现有模块审计能够记录部分业务生命周期，但 model、Prompt、policy、latency 和 actor 信息不完整，部分正文和上游错误保存过多。所有事件统一 fail-open 会让高风险 SQL 在没有审计证据时执行；所有事件统一 fail-closed 又会让低风险读取被审计故障阻断。

## 决策

1. ai-core 返回 provider、model、latency、token usage、finish reason 和 provider request id。
2. Prompt 元数据记录 name、version 和 content hash，不保存完整 Prompt。
3. guardrail 记录 policyVersion 和稳定 violation code。
4. 业务审计表继续归各模块所有，通过共享上下文统一 request、creator actor、action actor、model、Prompt、policy 和 trace 元数据。
5. Data 外部 SQL 执行前审计意图 fail-closed；没有意图记录不得执行。
6. Data 执行结果回写尽力完成；回写失败保留可诊断的 PENDING/UNKNOWN 运维状态。
7. 平台库内确认、取消和复核状态与审计同事务，fail-closed。
8. 模型生成、低风险读取和安全诊断审计 fail-open，但必须记录可观测错误。
9. 默认 7 天后匿名化问题、SQL 和错误详情，30 天后删除审计元数据；允许配置覆盖，但敏感正文不得无限保留。
10. 客户端错误不得包含 SQL、provider 原文、内部类名、堆栈或原始 cause。

## 后果

- Flyway V12 增加审计 v2 和 retention/anonymized_at 字段。
- Knowledge 必须区分 retrieved IDs 与 cited IDs；Support/Report/Resume 不再记录 `unknown` 模型。
- 审计清理失败产生告警，但不改变已提交的业务状态。
- 不建设万能审计事件表或通用观测平台。

## 验证

- 审计字段来自真实模型调用、Prompt 模板和 guardrail 结果。
- 高风险动作在审计写入失败时不执行或不改变状态。
- 低风险读取在审计故障时按设计降级。
- V11→V12、匿名化和删除任务在真实 PostgreSQL 通过。
- API 错误响应不泄露内部信息。
