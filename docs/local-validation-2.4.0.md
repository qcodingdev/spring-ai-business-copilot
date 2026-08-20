# 2.4.0-SNAPSHOT 本地验证记录

> 验证日期：2026-08-20（Asia/Shanghai）
> 性质：2.4 开发快照的可复现本地证据，不是正式发布签字。
> 版本：Maven 与前端均为 `2.4.0-SNAPSHOT`。

## 结论

五个现有 Copilot 的企业就绪实时检查、整改路由、服务端重检、追加式快照和保留期闭环已在本地通过。
Knowledge 过期/冲突/索引失败不再进入检索，来源问题可经人工确认执行全量恢复；孤儿索引任务的迟到结果
不能删除或覆盖替换任务的向量和生命周期状态。本地发布前门禁全部通过。

## 验证结果

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| Java 21 Maven + SBOM | 通过 | `./mvnw verify -Psbom`，13/13 模块成功，Surefire 报告共 479 项测试，0 失败/0 错误/0 跳过 |
| Flyway | 通过 | 32 个迁移校验；空库 V1→V32 及 V7/V28/V31→V32 升级路径通过 |
| PostgreSQL + pgvector | 通过 | 真实 `pgvector/pgvector:pg16` Testcontainer 25/25，包含 13 项就绪探针 SQL、V32 快照不可修改、索引租约隔离、向量/状态原子回滚与内容安全校验 |
| MySQL 只读目标 | 通过 | MySQL 8.4 随全量 `verify` 通过；MySQL 5.7 独立回归 1/1 通过 |
| 五模块固定评测 | 通过 | Data 18、Knowledge 12、Support 12、Report 12、Resume 13，共 67 条 |
| 前端静态与单测 | 通过 | Node 22.22.3；typecheck、ESLint 零 warning、Vitest 6 文件/11 测试、Vite 生产构建 |
| 端到端 | 通过 | Playwright 单 worker，桌面与移动端共 46/46；包含 Admin 就绪快照、整改路由和 Knowledge 全量恢复 |
| Docker 构建与安全运行 | 通过 | 镜像 `sha256:04803a2d94ca7394fb2718294e9a5791d21ef17eca86e511520f24faded934e9`；UID/GID `10001:10001`、只读根、`cap_drop=ALL`、`no-new-privileges`、healthy |
| Docker 业务冒烟 | 通过 | health、CSRF 登录、认证工作台、Data Schema；日志 `ERROR/Exception` 计数为 0 |
| Docker 容量冒烟 | 通过 | 50 请求、并发 5、失败 0、P95 `0.182676s`（门限 `1.5s`） |
| Docker 就绪证据 | 通过 | 容器内管理员检查为 `READY`、13 项；快照保存并从历史读回成功 |
| 交付文本检查 | 通过 | Shell 语法、Compose `config --quiet`、`git diff --check` |

CycloneDX 已生成 XML 和 JSON，共 199 个组件。Docker 验证使用独立项目名、端口和数据卷；验证后已删除本轮
容器、网络、数据卷和应用镜像，预存 Docker 资源未改变。

## 未由本地证据覆盖

- GitHub 远程 CI、Dependency Review 和 Trivy 必须在推送后由远端工作流证明。
- 真实 Chat/Embedding 模型、供应商租户、企业身份源、生产网络、备份恢复和生产容量需由部署方在自有环境验收。
- 当前仍是 `2.4.0-SNAPSHOT`；未创建 `v2.4.0` 长期分支、标签或 Release，也不应在正式发布门禁完成前替换 `v2.3.1` 稳定基线。
