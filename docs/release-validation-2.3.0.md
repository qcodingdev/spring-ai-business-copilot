# 2.3.0 正式发布验证报告

> 验证日期：2026-08-13
>
> 发布对象：Spring AI Business Copilot 2.3.0
> 范围：五模块企业闭环、Vue 双语工作台、V29-V31 数据迁移、交付与安全门禁

## 结论

2.3.0 的本地发布候选已通过前端静态/单元/生产构建、全 Reactor 测试、固定评测、
PostgreSQL 迁移矩阵、MySQL 查询目标兼容、CycloneDX SBOM 和正式容器构建门禁。
远端 Pull Request 必须继续通过 GitHub Actions 的浏览器 E2E、依赖审查和 Trivy 扫描后
才能合入主干；标签只允许创建在主干门禁成功的提交上。

## 已执行门禁

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| 前端 `npm run check` | 通过 | TypeScript、ESLint、Vitest 5 文件/9 测试、生产构建 |
| Maven `verify -Psbom` | 通过 | 13 个 Reactor 模块全部成功；应用模块 77 测试零失败；生成 2.3.0 JAR |
| 固定评测数据集 | 通过 | Data、Knowledge、Support、Report、Resume 固定样本门禁纳入 Maven/CI |
| PostgreSQL 16 + pgvector | 通过 | 17/17；空库 V1→V31、早期 V7→V31、v2.2.1 V28→V31、Support 原子复核领取均通过 |
| MySQL 8.4 查询目标 | 通过 | 只读外部业务查询集成测试零失败 |
| MySQL 5.7 查询目标 | 通过 | 只读外部业务查询集成测试零失败 |
| CycloneDX SBOM | 通过 | `target/bom.json`、`target/bom.xml` 由正式 Maven 门禁生成 |
| Docker 镜像 | 通过 | 正式 Dockerfile 构建成功；运行用户 `10001:10001` |
| Compose 运行时 | 通过 | PostgreSQL 与应用健康；Flyway `V31 enterprise workflow closure` 成功 |
| 认证业务冒烟 | 通过 | 健康、CSRF 登录、已认证工作台与 Data Schema 可达 |
| 打包镜像浏览器 E2E | 通过 | desktop 21/21、mobile 21/21，共 42/42 |

本次验证使用独立 Compose 项目 `business-copilot-v23-release` 和独立宿主端口；结束后只删除
该项目创建的容器、网络、数据卷和两张候选镜像，未改动既有 `examples-*` 或其他项目资源。

## 数据库迁移结论

- V29 增加 locale 审计和外部连接安全字段。
- V30 增加知识质量复核维度。
- V31 增加 Data 治理/结果交接、Support 回写恢复、Report 调度租约、HR 用途化授权、
  评估复核、面试与入职状态约束。
- Flyway 历史不改写；升级前必须备份，回退采用旧应用与数据库备份恢复，不以删除 V31
  代替回滚。

## 远端发布门禁

Pull Request 必须全部成功：`verify`、MySQL 5.7/8.4 矩阵、dependency review、
filesystem/container Trivy。合并后主干 CI 再次成功，才能创建不可变 `v2.3.0` 标签和
GitHub Release。最终 PR、主干运行和 Release 链接记录在 Release 页面，避免在候选提交中
预写尚未发生的结果。

## 环境责任边界

真实模型、Jira/Notion/ServiceNow/ATS 租户、企业身份源、密钥托管、生产网络、备份恢复、
容量压测和法务保留策略只能由部署方在自己的沙箱/生产环境验收。项目不会用模拟数据把这些
外部责任写成已通过；代码侧提供失败关闭、人工确认、审计和恢复路径。
