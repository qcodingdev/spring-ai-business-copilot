# 2.3.1 正式发布验证报告

> 验证日期：2026-08-15
>
> 发布对象：Spring AI Business Copilot 2.3.1
> 范围：Notion 完整遍历、外部连接契约、HR 复核权限回归、持续安全维护与正式交付

## 结论

2.3.1 正式发布候选已通过前端静态/单元/生产构建、全 Reactor 测试、固定评测、
PostgreSQL 迁移矩阵、MySQL 5.7/8.4 查询目标、CycloneDX SBOM、正式容器运行和打包态
浏览器 E2E。该版本不增加业务模块或数据库迁移，可从 `v2.3.0` 原地升级。

本机未预装 Trivy，验证期间 Docker Hub 与 GHCR 的 Trivy 官方镜像拉取均超时，因此本地
不把 Trivy 写成通过。Pull Request 和主干 CI 中的 `container-security` 是发布强制门禁，
会对源码依赖、密钥、配置和正式镜像执行 HIGH/CRITICAL 扫描；新增的定时工作流继续在
发布后每周复查。

## 已执行门禁

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| 前端 `npm run check` | 通过 | Node 22.22.3；TypeScript、ESLint、Vitest 5 文件/9 测试、生产构建 |
| Vite 预览 E2E | 通过 | desktop 21/21、mobile 21/21，共 42/42 |
| Maven `verify -Psbom` | 通过 | 正式 `2.3.1`；13 个 Reactor 模块全部成功；应用模块 78 测试零失败 |
| 固定评测数据集 | 通过 | Data 18、Knowledge 12、Support 12、Report 12、Resume 13，共 67 条 |
| 外部适配器契约 | 通过 | HTTP 字节/重定向 2 条、Notion/SharePoint/Confluence 5 条、Support 五厂商 5 条 |
| PostgreSQL 16 + pgvector | 通过 | 17/17；空库 V1→V31、早期 V7→V31、v2.2.1 V28→V31 均通过 |
| MySQL 8.4 查询目标 | 通过 | 只读查询、元数据与脱敏集成测试零失败 |
| MySQL 5.7 查询目标 | 通过 | 正式 2.3.1 单独兼容门禁零失败 |
| CycloneDX SBOM | 通过 | `target/bom.json`、`target/bom.xml` 由正式 Maven 门禁生成 |
| GitHub 配置 | 通过 | Dependabot 与定时安全工作流 YAML 可解析；`git diff --check` 通过 |
| Docker 镜像 | 通过 | 正式 Dockerfile 构建成功；运行用户 `10001:10001`；内置健康检查 |
| Compose 运行时 | 通过 | 独立 PostgreSQL 与应用健康；Flyway V1-V31 初始化成功 |
| 认证业务冒烟 | 通过 | 健康、CSRF 登录、已认证工作台与 Data Schema 可达 |
| 打包镜像浏览器 E2E | 通过 | desktop 21/21、mobile 21/21，共 42/42 |
| 本地 Trivy | 未执行 | 两个官方镜像源拉取超时；由 PR/主干 `container-security` 强制补齐 |

本次容器验证使用独立 Compose 项目 `business-copilot-v231-release`、应用端口 `18081` 和
PostgreSQL 端口 `15433`。验证结束后已删除该项目创建的容器、网络、数据卷和候选镜像；
既有 `examples-app-1` 与 `examples-postgres-1` 未改动且仍保持健康。

## 2.3.1 回归结论

- Notion 使用当前 API 版本头，搜索结果和 block children 均按 opaque cursor 分页；
  `has_children` 内容递归读取，同时受页数、条目数、深度、响应字节和任务超时预算约束。
- SharePoint、Confluence、Jira、Zendesk、ServiceNow、飞书和企业微信的读取/回写契约有
  可执行测试，认证头、分页、ACL、HTTP 方法、路径和幂等键均被断言。
- HR REVIEWER 打开候选人复核时不再加载该流程不需要的已确认岗位接口；桌面和移动端
  回归测试同时断言该非必要请求不会发生。
- 版本统一为正式 `2.3.1`，无 `2.3.1-SNAPSHOT` 遗留；2.3.0→2.3.1 不产生 Flyway 变化。

## 远端发布门禁

Pull Request 必须全部成功：`verify`、桌面/移动 E2E、MySQL 5.7/8.4 矩阵、dependency
review、filesystem/container Trivy。合并后主干 CI 必须再次成功，正式 `v2.3.1` 长期分支、
同名标签和 GitHub Release 只能指向该主干发布提交。最终 PR、主干运行和 Release 链接记录
在远端，不在候选提交中预写尚未发生的结果。

## 环境责任边界

真实模型、Notion/SharePoint/Confluence/Jira/Zendesk/ServiceNow/飞书/企业微信租户、企业
身份源、生产密钥托管、备份恢复、容量压测和法务保留策略需要部署方在自己的沙箱或生产
环境验收。项目不会用 Mock 契约替代供应商租户验收；代码侧提供失败关闭、预算限制、人工
确认、审计和恢复路径。
