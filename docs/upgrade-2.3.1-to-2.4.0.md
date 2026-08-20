# 从 2.3.1 升级到 2.4.0

> 当前文档随 `2.4.0-SNAPSHOT` 开发线维护。正式 `v2.4.0` 发布前仍应以 `v2.3.1` 作为
> 稳定生产基线；发布后按正式标签、镜像摘要和 Release 验证记录执行升级。

## 变化范围

- Maven 与前端开发版本统一为 `2.4.0-SNAPSHOT`。
- Admin 增加五模块企业就绪实时检查、整改路由、服务端重检和不可变证据历史。
- Flyway V32 新增 `enterprise_readiness_snapshots`；只允许追加和保留期删除，数据库触发器
  拒绝就地更新。
- Knowledge 文本、关键词和向量检索统一排除已过期或存在冲突的当前资料；外部来源内容未变
时续期 30 天。来源问题卡片经人工确认后执行全量恢复，并对超过租约窗口的索引任务安全建立替换任务。
  向量与租约/文档完成状态在同一事务内提交，超时旧工作线程不能回写替换任务的向量或状态。
- 不增加第六个模块，不改变既有 SQL、客服、报告、招聘确认边界，也不增加自动外发动作。

## 升级前

1. 记录当前 `v2.3.1` JAR/镜像摘要、配置、外部连接白名单和数据库备份位置。
2. 完成 PostgreSQL 物理备份或经验证的逻辑备份，并确认恢复演练责任人。
3. 确认运行账号可执行 Flyway V32 的建表、索引、函数和触发器 DDL。
4. 评估快照保留要求；证据仅包含检查编号、模块、状态、数量、阈值和整改路由，不包含业务正文。

## 新增配置

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `BUSINESS_COPILOT_APPLICATION_VERSION` | `2.4.0-SNAPSHOT` | 写入快照的应用版本；正式发布改为 `2.4.0` |
| `BUSINESS_COPILOT_READINESS_SNAPSHOT_VALIDITY` | `24h` | 单份就绪证据的有效窗口 |
| `BUSINESS_COPILOT_READINESS_SNAPSHOT_RETENTION` | `90d` | 历史快照保留时间，必须长于有效窗口 |
| `BUSINESS_COPILOT_READINESS_STALE_OPERATION_AFTER` | `15m` | 领取态、运行态和回写处理态的超时阈值 |
| `BUSINESS_COPILOT_READINESS_EXPIRED_RESULT_GRACE` | `1h` | Data 过期结果的清理宽限，与默认清理调度一致 |
| `BUSINESS_COPILOT_READINESS_REVIEW_BACKLOG_AFTER` | `24h` | Report/HR 人工复核和入职必办任务积压阈值 |
| `BUSINESS_COPILOT_READINESS_FAILED_RUN_LOOKBACK` | `7d` | Knowledge/Report 失败运行观察窗口 |
| `BUSINESS_COPILOT_READINESS_CLEANUP_CRON` | `0 45 3 * * *` | 过期证据保留清理计划 |

## 执行与核验

部署新应用后，由 Flyway 顺序执行 V32，不要手工改写 `flyway_schema_history`。管理员登录后：

1. 打开“系统管理 → 企业就绪”。
2. 确认 13 项检查均返回稳定编号，并检查 `READY`、`ATTENTION` 或 `BLOCKED` 总体状态。
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

预期最新迁移为 `32`，并存在 `trg_enterprise_readiness_snapshot_immutable`。

## 回退

V32 是追加表、索引、函数和触发器，旧版 2.3.1 应用不会访问这些对象。短期应用回退可停止
2.4 应用并部署已记录的 `v2.3.1` 制品，同时保留 V32 对象；不要删除 Flyway 历史行。若组织
要求数据库也完全回到升级前状态，使用升级前已验证备份恢复，并按变更流程处理升级期间新增
业务数据。应用回退不应被描述为数据库回退。

## 验证边界

本地 Maven、前端、PostgreSQL/MySQL、Docker 和 Mock 契约通过，不等于真实模型、供应商租户、
企业身份源、生产网络、容量或灾备验收。部署方仍需在自己的环境完成这些门禁。
