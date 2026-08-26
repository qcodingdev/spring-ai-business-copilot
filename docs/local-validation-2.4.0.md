# 2.4.0 正式发布本地验证记录

> 验证日期：2026-08-26（Asia/Shanghai）
> 性质：2.4.0 正式候选提交的可复现本地证据，不替代部署方生产验收。
> 版本：Maven、前端与应用就绪快照默认版本均为 `2.4.0`。

## 结论

五个现有 Copilot 的配置前置条件、企业就绪实时检查、整改路由、服务端重检、追加式快照和保留期闭环
已在本地通过。默认无模型配置的容器返回 `NOT_CONFIGURED`，不再把空系统误报为 `READY`；Knowledge
来源 ACL 可在正文未变化时传播，且同一来源只允许一个活动同步任务；人工确认的全量恢复可条件取消
超过阈值的旧活动任务。Report/HR 复核与入职事项改用持久化
截止时间。当前代码级、浏览器和独立容器本地门禁通过。

## 验证结果

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| Java 21 Maven + SBOM | 通过 | `./mvnw verify -Psbom`，13/13 模块成功，Surefire 报告共 485 项测试，0 失败/0 错误/0 跳过 |
| Flyway | 通过 | 33 个迁移校验；空库及 V1/V28/V31/V32→V33 升级路径通过 |
| PostgreSQL + pgvector | 通过 | 真实 `pgvector/pgvector:pg16` Testcontainer 28/28，包含 13 项运行探针 SQL、V33 同步唯一约束、陈旧任务确认恢复和既有重复任务收敛、截止时间与索引校验 |
| MySQL 只读目标 | 通过 | MySQL 8.4 随全量 `verify` 通过，1/1；本轮未单独重跑 MySQL 5.7 |
| 五模块固定评测 | 通过 | Data 18、Knowledge 12、Support 12、Report 12、Resume 13，共 67 条 |
| 前端静态与单测 | 通过 | Node 22.22.3；typecheck、ESLint 零 warning、Vitest 6 文件/13 测试、Vite 生产构建 |
| 端到端 | 通过 | Playwright 单 worker，桌面与移动端共 46/46；包含 Admin 就绪快照、整改路由和 Knowledge 全量恢复 |
| Docker 构建与安全运行 | 通过 | 正式候选镜像 `sha256:1100678086d3b329bf96b8e2e2693e42c2302f80eb981b64b3cdac12f0791be7`；UID/GID `10001:10001`、只读根、`cap_drop=ALL`、`no-new-privileges`、healthy |
| Docker 业务冒烟 | 通过 | health、CSRF 登录、认证工作台、Data Schema；日志 `ERROR/Exception` 计数为 0 |
| Docker 容量冒烟 | 通过 | 默认 `/api/admin/readiness`，50 请求、并发 5、失败 0、P95 `0.244601s`（门限 `1.5s`） |
| Docker 就绪证据 | 通过 | 无模型配置时为 `NOT_CONFIGURED`、schema v2、20 项；64 位哈希快照保存并从历史按编号/哈希读回成功 |
| 交付文本检查 | 通过 | Shell 语法、Compose `config --quiet`、`git diff --check` |

CycloneDX 已生成 XML 和 JSON，共 199 个组件。Docker 验证使用独立项目名
`bcv240audit0826fix`、端口和数据卷；验证后已删除本轮容器、网络、数据卷和应用镜像，预存 Docker
资源未改变。

## 未由本地证据覆盖

- GitHub 远程 CI、Dependency Review 和 Trivy 由 `v2.4.0` 发布 PR 与主干工作流证明，具体运行链接记录在 GitHub Release。
- 真实 Chat/Embedding 模型、供应商租户、企业身份源、生产网络、备份恢复和生产容量需由部署方在自有环境验收。
