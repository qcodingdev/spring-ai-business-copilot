# Spring AI Business Copilot 运行与恢复手册

> 适用版本：2.1 开发线
> 目标：为现有五个 Copilot 提供可执行的观测、容量基线、备份和恢复演练，不承诺未经业务验收的生产 SLA。

## 1. 调用链与中文日志

每个 HTTP 业务请求使用 `requestId` 和 `actorId`；进入模型调用后增加 `aiCallId` 和固定的 `aiOperation`。典型链路如下：

```text
业务请求开始：方法=POST，路径=/api/... requestId=... actorId=...
AI 调用开始 aiCallId=... aiOperation=data.sql-generation
AI 调用完成 aiCallId=... 耗时毫秒=...
业务请求完成：状态=200，耗时毫秒=... requestId=...
```

`aiOperation` 只能使用代码内固定值，不能使用问题、Prompt 或文件名，避免敏感数据进入日志和指标标签。第三方框架日志保持依赖默认语言，项目自身新增日志使用中文。

## 2. 指标与告警

Admin/Reviewer 可访问 `/actuator/metrics`。核心指标：

| 指标 | 含义 | 建议告警起点 |
|---|---|---|
| `business.copilot.ai.calls` | 按类型、固定操作、状态统计调用数 | 5 分钟失败率持续超过 20% |
| `business.copilot.ai.latency` | Chat/Embedding 调用耗时 | P95 连续 10 分钟超过业务阈值 |
| `business.copilot.ai.tokens` | 提供方返回的输入/输出 token | 单小时异常增长或超出预算 |
| `http.server.requests` | HTTP 请求状态与耗时 | 5xx 比例或 P95 异常 |
| `hikaricp.connections.*` | 平台和业务查询连接池 | pending 持续大于 0 |

需要 Prometheus 的部署可加入 Micrometer registry exporter，并继续通过专用只读监控账号或反向代理鉴权，不能公开暴露指标端点。

## 3. AI 故障保护

- Chat 默认超时 45 秒，Embedding 默认超时 30 秒。
- Spring AI 瞬时故障最多尝试 3 次，客户端错误不重试。
- AI Core 默认最多允许 8 个并发外部调用，等待 2 秒仍无许可则返回中文繁忙提示。
- Chat 与 Embedding 分别统计最近 10 次调用；至少 5 次后失败率达到 50% 时熔断 30 秒，再进入半开探测。
- 熔断只阻止外部模型调用，不绕过模块 guardrail，也不自动改变业务状态。

所有阈值都可通过 `BUSINESS_COPILOT_AI_*`、`SPRING_AI_*_TIMEOUT` 和 `SPRING_AI_RETRY_*` 环境变量覆盖。修改前必须用真实业务流量复测。

## 4. 固定评测门禁

```bash
./scripts/check-evaluation-datasets.sh
./mvnw --batch-mode --no-transfer-progress verify
```

规模检查只防止数据集被意外删减；每条期望结果由 JUnit 执行。当前覆盖 Data SQL 安全、Knowledge 引用、Support 禁止承诺、Report 来源一致性和 Resume 招聘合规。

## 5. 容量基线

启动 Compose 后执行不产生模型费用的认证读请求基线：

```bash
BUSINESS_COPILOT_CAPACITY_REQUESTS=100 \
BUSINESS_COPILOT_CAPACITY_CONCURRENCY=10 \
BUSINESS_COPILOT_CAPACITY_P95_SECONDS=1.5 \
./scripts/capacity-smoke-test.sh
```

该脚本用于发现 Web 线程池、认证和数据库连接池的明显退化，不替代企业真实压测。模型容量必须在受控账号和预算下单独验证。

## 6. 备份与恢复演练

生成不可覆盖、权限为 600 的自定义格式备份：

```bash
./scripts/backup-postgres.sh backups/business-copilot-$(date +%Y%m%d-%H%M%S).dump
```

在一次性 PostgreSQL/pgvector 容器中恢复并核对 Flyway 与核心业务表：

```bash
./scripts/backup-restore-drill.sh backups/business-copilot-YYYYMMDD-HHMMSS.dump
```

恢复演练不会连接或覆盖现有数据库，结束后删除一次性容器。生产恢复必须先停止写入、保留原库快照，在新数据库恢复并完成五模块 smoke 后再切换连接；禁止直接在原库上执行 `pg_restore --clean`。

## 7. 发布前最小检查

1. 固定评测、全量 Maven、PostgreSQL/MySQL Testcontainers 全绿。
2. 前端、Shell、容器安全、SBOM 和依赖审查全绿。
3. 备份文件可在一次性容器恢复。
4. 容量基线无失败且 P95 未退化。
5. 使用真实 Chat/Embedding 完成五模块 smoke，并检查中文调用链日志、token 和 latency 指标。
