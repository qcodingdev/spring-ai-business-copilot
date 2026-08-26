# 从 2.3.1 升级到 2.4.0

> 本文档对应正式 `v2.4.0`。升级前应核对正式标签、Release 验证记录和部署方自己的
> 备份恢复、真实模型、身份源与供应商沙箱门禁。

## 变化范围

- Maven、前端与应用就绪快照默认版本统一为 `2.4.0`。
- Admin 增加模型/五模块前置条件、运行检查、整改路由、服务端重检和追加式证据历史。
- Flyway V32 新增 `enterprise_readiness_snapshots`；只允许追加和保留期删除，数据库触发器
  拒绝就地更新。
- Flyway V33 增加 `NOT_CONFIGURED` 状态、Knowledge 同步单活动任务约束、readiness 统计索引、
  Report/HR 复核截止时间和入职事项独立截止时间。
- Knowledge 文本、关键词和向量检索统一排除已过期或存在冲突的当前资料；外部来源内容未变
  时续期 30 天。来源问题卡片经人工确认后执行全量恢复，并对超过租约窗口的索引任务安全建立替换任务。
  向量与租约/文档完成状态在同一事务内提交，超时旧工作线程不能回写替换任务的向量或状态。
- 不增加第六个模块，不改变既有 SQL、客服、报告、招聘确认边界，也不增加自动外发动作。

## 升级前

1. 记录当前 `v2.3.1` JAR/镜像摘要、配置、外部连接白名单和数据库备份位置。
2. 完成 PostgreSQL 物理备份或经验证的逻辑备份，并确认恢复演练责任人。
3. 确认运行账号可执行 Flyway V32/V33 的建表、字段、约束、索引、函数和触发器 DDL。
4. 评估快照保留要求；证据仅包含检查编号、模块、状态、数量、阈值和整改路由，不包含业务正文。

## 新增配置

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `BUSINESS_COPILOT_APPLICATION_VERSION` | `2.4.0` | 写入快照的应用版本；自定义构建应覆盖为可追溯制品版本 |
| `BUSINESS_COPILOT_READINESS_SNAPSHOT_VALIDITY` | `24h` | 单份就绪证据的有效窗口 |
| `BUSINESS_COPILOT_READINESS_SNAPSHOT_RETENTION` | `90d` | 历史快照保留时间，必须长于有效窗口 |
| `BUSINESS_COPILOT_READINESS_STALE_OPERATION_AFTER` | `15m` | 领取态、运行态和回写处理态的超时阈值 |
| `BUSINESS_COPILOT_READINESS_EXPIRED_RESULT_GRACE` | `1h` | Data 过期结果的清理宽限，与默认清理调度一致 |
| `BUSINESS_COPILOT_READINESS_FAILED_RUN_LOOKBACK` | `7d` | Knowledge/Report 失败运行观察窗口 |
| `BUSINESS_COPILOT_READINESS_CLEANUP_CRON` | `0 45 3 * * *` | 过期证据保留清理计划 |
| `BUSINESS_COPILOT_REPORT_REVIEW_SLA` | `24h` | Report 草稿人工复核截止时间 |
| `BUSINESS_COPILOT_HR_REVIEW_SLA` | `24h` | 候选人评估人工复核截止时间 |

## 执行与核验

部署新应用后，由 Flyway 顺序执行到 V33，不要手工改写 `flyway_schema_history`。管理员登录后：

1. 打开“系统管理 → 企业就绪”。
2. 确认 7 项配置前置条件和 13 项运行检查均返回稳定编号；缺少模型或关闭模块时应为
   `NOT_CONFIGURED`，前置条件通过后再检查 `READY`、`ATTENTION` 或 `BLOCKED`。
3. 对非通过项使用整改入口进入既有模块处理，再返回并重新检查。
4. 填写本次验收用途，保存快照；确认快照编号、操作者、状态、内容哈希和有效期可在历史中查看。
5. 用非管理员账号验证实时检查和快照接口均返回 403。

数据库只读核验：

```sql
SELECT version
FROM flyway_schema_history
WHERE success = TRUE
ORDER BY installed_rank DESC
LIMIT 1;

SELECT trigger_name
FROM information_schema.triggers
WHERE event_object_table = 'enterprise_readiness_snapshots';
```

预期最新迁移为 `33`，并存在 `trg_enterprise_readiness_snapshot_immutable`。

## 回退

V32/V33 是追加表、字段、约束、索引、函数和触发器，旧版 2.3.1 应用不会访问这些对象。短期
应用回退可停止 2.4 应用并部署已记录的 `v2.3.1` 制品，同时保留 V32/V33 对象；不要删除
Flyway 历史行。若组织
要求数据库也完全回到升级前状态，使用升级前已验证备份恢复，并按变更流程处理升级期间新增
业务数据。应用回退不应被描述为数据库回退。

## 验证边界

当前本地验证记录中的 Maven、前端、PostgreSQL/MySQL、Docker 和 Mock 契约通过，不等于真实模型、供应商租户、
企业身份源、生产网络、容量或灾备验收。部署方仍需在自己的环境完成这些门禁。
