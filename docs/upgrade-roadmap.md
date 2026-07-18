# Spring AI Business Copilot 2.0 升级执行路线

> 状态：本地代码、容器、迁移、真实模型和浏览器主流程已收口；等待用户决定是否提交并推送 2.0。  
> 分支：`feature/v2.0-business-hardening`  
> 版本：`2.0.0-SNAPSHOT`  
> 最近审核：2026-07-18

## 1. 版本决策

本轮不再按 v1.2～v1.6 分散发布，可信执行底座和五个业务模块的纵向升级统一作为 2.0 交付。

2.0 不增加第六个 Copilot，不建设多租户、复杂 IAM、商业 BI、模型平台、工作流平台或微服务体系。目标是把 Data、Knowledge、Support、Report、Resume 升级成可信、可诊断、可评测、可部署的业务样板。

## 2. 已完成范围

### 2.1 共性阻断与可信底座

- 修复 Spring Security 7 CSRF 不兼容，登录后的 POST/PATCH/DELETE 可真实执行。
- 安全响应、业务日志、校验提示、链路状态和工作台提醒中文化。
- schema/table/column 三级白名单，同名跨 schema 失败关闭。
- 普通函数默认拒绝；聚合函数显式 allowlist；相对日期转换为固定日期字面量。
- LIMIT 只接受受限整数字面量；JDBC 限制 timeout、行数、列数、fetch size 和结果字节数。
- SQL candidate 与高风险业务对象使用绑定 actor、对象、状态、有效期的一次性 token。
- Data、Support、Report、Resume 使用条件状态更新，阻止过期、重放和并发竞争。
- 五模块由各自 AutoConfiguration 显式装配，不依赖宿主根包扫描。
- 审计记录 actor、provider/model、Prompt 身份、policy、latency、token usage 和保留策略。
- Data 外部只读查询目标支持 PostgreSQL/MySQL，生产模式要求独立 reader。
- `.env` 不进入版本库，示例配置与 chat/embedding 的真实 Spring 环境变量对齐。

### 2.2 Data Copilot

- PostgreSQL/MySQL 共用只读、安全、资源和脱敏契约。
- 当前业务日期进入 prompt，相对日期生成固定边界，不放宽数据库函数策略。
- V18 提供 14 个月的虚构客户、商品、订单、退款和营销事件。
- 浏览器完成“高价值客户”生成、规则通过、确认执行、5 行结果和中文 AI 解释。

### 2.3 Knowledge Copilot

- 共享 `platform/document-processing` 有界解析 TXT、Markdown、PDF、DOCX。
- 文档使用逻辑 ID、版本、current version、启用和删除/提升状态。
- 数据库持久化异步索引支持 claim、轮询、失败、重试和重建。
- V17 使用可变维度向量列；检索按 embedding model + dimension 隔离历史向量。
- PostgreSQL 全文检索与 pgvector 混合召回。
- LLM 只选择 chunk ID，引用 excerpt 由服务端从本次召回内容填充。
- 任务 owner/admin 校验、敏感信息处理、引用完整性和 groundedness 评测已覆盖。

### 2.4 Support Copilot

- 分类、情绪、紧急程度和状态为受控枚举。
- 工单和草稿使用 expected-state 条件状态机。
- 草稿绑定知识版本和引用，支持人工编辑、确认、取消和反馈。
- owner 可复核本人对象，Reviewer 只处理明确复核动作。

### 2.5 Report Copilot

- 来源保存不可变快照、provider、版本、时间、时区、单位、有效期和 freshness。
- CSV/JSON 有界导入、预览和文件生成。
- 事实、来源行动项与 AI 建议分离。
- 确认后可导出中文 Markdown 和转义 HTML。

### 2.6 Resume Copilot

- JD 使用逻辑 ID、标准版本、生效时间和 current version。
- 支持 TXT、Markdown、PDF、DOCX 的 JD/简历文件输入。
- 脱敏 submission 支持过期、主动删除和定时清理。
- Assessment 绑定标准版本，支持人工修订、复核结果和反馈。
- 不提供总分、排名、通过概率或自动录用/淘汰。

### 2.7 工作台、示例和交付

- 五模块统一工作台暴露文件样例、上传/索引状态、人工编辑、来源导入、可信导出、复核和删除。
- 知识库、报告、JD 和简历使用可下载的虚构样例文件，不自动写入用户业务数据。
- 运行镜像使用非 root UID/GID 10001。
- Compose 使用只读根文件系统、`/tmp` tmpfs、capability drop 和 no-new-privileges。
- CI 配置前端语法、固定评测、PostgreSQL、MySQL 5.7/8.4、CycloneDX SBOM、依赖审查、Trivy 文件/镜像扫描和非 root 检查。

## 3. 2026-07-18 本地验收

| 门禁 | 结果 |
|---|---|
| Maven | 全量 `verify` 成功，317 tests / 0 failures / 0 errors / 0 skipped |
| PostgreSQL/pgvector | Testcontainers 成功 |
| Flyway | 空库 V1→V18、历史 V7→V18、现有库 V16→V18 成功 |
| MySQL | 8.4 独立只读目标成功 |
| 向量兼容 | 混合模型/维度检索不会跨维比较 |
| Docker | app/postgres healthy，已有用户数据保留 |
| CycloneDX SBOM | 本地 JSON/XML 生成成功 |
| 五模块真实模型 | 中文 release AI smoke 全部通过 |
| 浏览器 | 登录、CSRF、Data 示例完整闭环通过 |
| 静态门禁 | JavaScript、Shell、YAML、diff 检查通过 |

具体证据、修复原因和成熟度判断见 `docs/current-project-audit-2026-07-16.md`。

## 4. 用户决定推送后的强制门禁

1. 精确审核并暂存 2.0 文件；排除 `.env`、用户无关文件和本地生成物。
2. 提交并推送 `feature/v2.0-business-hardening`。
3. 新建独立 2.0 PR，不复用旧 1.x PR。
4. 远端 Maven、固定评测、PostgreSQL/pgvector 和 MySQL 5.7/8.4 全部通过。
5. CycloneDX SBOM 生成并上传。
6. dependency review、Trivy filesystem、容器构建、非 root 和 Trivy image scan 全部通过。
7. 检查所有 required checks 和 review thread。
8. 全绿后才允许合并 `main` 和创建正式 2.0 Release。

任一门禁失败，都在 2.0 分支修复并重跑；不带失败或跳过项合并主干。

## 5. 2.0 后续维护方向

2.0 合并后只做小步维护，不立即扩张平台：

- 根据真实语料评测调优检索阈值、拒答率和误阻断率。
- 增加容量压测、迁移/备份恢复演练和运行告警。
- 企业实际部署时替换内存用户为企业 IdP/SSO adapter，但不把 IAM 平台纳入本仓库。
- 仅在第二个真实 adapter 出现后抽象共享 SPI。
- 持续升级 action SHA、基础镜像和高危依赖。
