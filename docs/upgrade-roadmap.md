# Spring AI Business Copilot 2.2 企业质量闭环与接入升级路线

> 状态：2.1 已形成质量、韧性和公网产品化基线；2.2 在独立功能分支开发，尚未发布。
> 版本：`2.2.0-SNAPSHOT`
> 最近审核：2026-07-28

> **实现快照（2026-07-28）：** V22–V28、五模块企业 API、共享凭证引用、XLSX/HTML 解析、管理台闭环计数与 V1→V28 PostgreSQL 升级验证已完成。SharePoint、Confluence、Notion、S3/MinIO、Jira、Zendesk、ServiceNow、飞书、企微和 ATS 适配器代码已落地，但在获得部署方凭证并通过各自真实沙箱前，只能标记为“待外部联调”。扫描件 OCR 继续按实际资料需求启用独立运行时，不把原生 OCR 依赖强塞进默认发行包。

## 0. 2.2 当前升级范围

2.2 继续保持五个 Copilot，不增加第六模块，集中解决“真实用户反馈能否进入质量改进、企业资料能否安全接入、跨模块结果能否形成受控闭环”三个问题：

- Knowledge 回答返回稳定 `answerId`，用户可提交有帮助/无帮助反馈。
- 负反馈必须选择稳定原因；反馈绑定原问答操作者，同一操作者可幂等更新。
- 无依据、生成拒绝和负反馈进入 Admin/Reviewer 质量复核队列，为固定评测集扩充提供真实样本。
- 优先落地一个真实企业资料来源 adapter，支持幂等同步、更新和源端删除传播；第二个场景出现前不抽象通用连接器平台。
- 企业部署通过 OIDC/SSO adapter 消费已有用户和组声明，并把权限带入知识检索；本仓库不建设 IAM。
- Support/Report 只通过受控草稿、确认和审计回写真实系统，不开放模型任意工具执行。

## 1. 版本决策

上述能力改变了公开 API、数据库结构和企业使用闭环，不适合作为 2.1 修补项，因此启动 `2.2.0-SNAPSHOT`，开发分支为 `feature/v2.2-enterprise-feedback`。

2.2 不建设多租户、复杂 IAM、商业 BI、模型平台、工作流平台或微服务体系。共性 SPI 仍需至少两个业务模块或两个真实 adapter 复用后才能进入 platform。

## 2. 2.1 已完成基线

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

## 3. 2.0/2.1 历史验收

| 门禁 | 结果 |
|---|---|
| Maven | 全量 `verify -Psbom` 成功，332 tests / 0 failures / 0 errors / 0 skipped |
| PostgreSQL/pgvector | Testcontainers 成功 |
| Flyway | 空库 V1→V18、历史 V7→V18、现有库 V16→V18 成功 |
| MySQL | 5.7/8.4 独立只读目标成功 |
| 向量兼容 | 混合模型/维度检索不会跨维比较 |
| Docker | app/postgres healthy，已有用户数据保留 |
| CycloneDX SBOM | 本地 JSON/XML 生成成功 |
| 五模块真实模型 | 中文 release AI smoke 全部通过 |
| 浏览器 | 登录、CSRF、Data 示例完整闭环通过 |
| 静态门禁 | JavaScript、Shell、YAML、diff 检查通过 |

具体证据、修复原因和成熟度判断见 `docs/current-project-audit-2026-07-16.md`。

### 3.1 2026-07-22 本轮验证

| 门禁 | 结果 |
|---|---|
| 固定评测规模 | 5 组共 67 条，规模门禁通过 |
| Maven / SBOM | `verify -Psbom` 构建成功；338 tests / 0 failures / 0 errors / 10 skipped；生成 192 组件的 JSON/XML SBOM |
| 新增安全边界 | AI Core 调用协调器与指标授权定向测试通过；Reviewer 可读指标，Operator/匿名用户被拒绝 |
| 静态门禁 | 前端语法、Shell 语法、Compose 配置和 `git diff --check` 通过 |
| 容器集成 | 本地沙箱不能访问 Docker socket，PostgreSQL/pgvector 9 条与 MySQL 1 条自动跳过；提权重跑因审批通道中断未执行 |
| 容量/恢复/真实模型 | 已提供脚本与运行手册，本轮未连接运行中应用、备份或真实模型，仍是发布前门禁 |

因此当前结论是“2.1 本地代码与非容器门禁通过”，不是“生产发布已完成”。容器集成、容量、恢复演练、真实模型和远端 CI 仍需在可访问 Docker 与受控凭证的发布环境执行。

### 3.2 2026-07-24 公网产品化实施

- 完成 `development / self-hosted / public-demo` 单代码线运行模式，公网模式关闭上传、真实业务写入和技术配置入口。
- 完成五模块 15 个服务端版本化场景、预生成示例结果、安全投影和确认后执行流程。
- 完成 V19–V21：Knowledge 可见范围、system managed 数据、场景/初始化任务、额度和 AI 用量成本。
- 完成 Admin 幂等初始化、持久任务、双确认恢复、保留策略和私有运维脚本。
- Resume 用户定位升级为 HR Copilot，保留内部模块兼容；新增岗位草稿、标准编辑、证据缺口、面试题和人工复核。
- 客服增加复核队列、风险/紧急度筛选和修订差异；报告改为业务摘要、风险和行动项优先。
- 完成 Railway Docker 部署配置、受控变量清单、只读业务账号脚本和开放域名前门禁。
- Docker 实测 MySQL 8.4、空库 V1→V21、历史 V7→V21、pgvector 检索和完整 demo 初始化重复执行均通过。

公网域名、Railway 重启持久化/备份回滚和五模块真实模型 smoke 仍需在用户提供 Railway Project 与受控 Chat/Embedding Key 后执行，不将本地代码完成表述为公网发布完成。

## 4. 2.1 基线发布门禁

1. 精确审核并暂存 2.1 文件；排除 `.env`、备份、用户无关文件和本地生成物。
2. 本地固定评测、全量 Maven/SBOM、前端与 Shell 静态门禁全部通过。
3. 使用一次性容器完成最新 PostgreSQL 备份恢复演练，不连接或覆盖现有数据库。
4. 容量基线无失败且 P95 未退化；在受控预算下完成五模块真实模型 smoke。
5. 推送独立 2.1 分支并创建 PR，不直接在 `main`/`master` 上交付开发改动。
6. 远端 PostgreSQL/pgvector、MySQL 5.7/8.4、SBOM、dependency review、Trivy 和非 root 镜像检查全绿。
7. 检查 required checks 和 review thread；全部通过后才允许合并和创建正式 Release。

任一门禁失败，都在 2.1 分支修复并重跑；不带失败或跳过项合并主干。

## 5. 2.2 交付切片

### 5.1 Knowledge 企业质量闭环

当前已实现：

- V22 `knowledge_answer_feedback`，绑定问答审计记录和反馈操作者。
- `POST /api/knowledge-copilot/answers/{answerId}/feedback`，支持幂等更新。
- `GET /api/knowledge-copilot/quality-queue`，仅 Admin/Reviewer 可读。
- V23 `knowledge_quality_reviews` 保存已处理、忽略、转知识维护三类人工处置，并记录操作者、说明、问题修订号和时间。
- `POST /api/knowledge-copilot/quality-queue/{answerId}/review` 使用单调递增的 `expectedIssueVersion` 做确定性并发判断，并同时绑定问题时间；新反馈会自动重新入队。
- `GET /api/knowledge-copilot/quality-metrics` 提供不含业务原文的反馈、待复核和处置低基数计数。
- 反馈备注和复核说明在持久化前复用 `SensitiveTextMasker`，遮蔽个人信息与凭据值。
- 工作台在当前答案下提交反馈，并明确提示管理员下一步复核；不自动修改文档或模型配置。
- Knowledge、Web 校验与角色权限定向测试已通过。

2026-07-28 验证证据：

| 门禁 | 结果 |
|---|---|
| Maven / SBOM | `test` 13 模块成功；398 tests / 0 failures / 0 errors，受限沙箱中 16 条容器用例跳过；`verify -Psbom` 生成 205 组件的 JSON/XML SBOM |
| PostgreSQL/pgvector | 在可访问 Docker 的环境中 15 条 Testcontainers 集成测试通过；空库 V1→V28、历史 V7→V28 成功，并验证五模块企业治理对象和失败关闭约束 |
| 企业业务场景 | Data 成本预算解析失败关闭、Knowledge 未映射 ACL 收紧为 Admin、Support 工单正文脱敏和 SLA、Report 多源待确认草稿、HR 授权/证据/禁止筛退均有定向测试 |
| 浏览器实测 | 隔离 PostgreSQL 上启动 2.2 应用，Admin 登录与 `/api/admin/diagnostics` 返回 200，13 项企业闭环状态完成渲染，控制台 0 error；截图已纳入中英文 README 和演示素材 |
| 反馈与处置约束 | 跨操作者绑定失败关闭；正/负反馈原因、修订号递增、旧处置重放拒绝、幂等更新和重新入队均有数据库证据 |
| 固定评测 | Data 18、Knowledge 12、Support 12、Report 12、Resume 13，共 67 条通过 |
| 静态门禁 | 9 个 JavaScript 文件、Shell 语法和 `git diff --check` 通过 |

下一步从复核队列选择脱敏样本加入固定评测集；必须人工确认，不直接把用户输入写入测试资源。按模型或 Prompt 版本拆分指标前，需要先验证真实排障需求，避免高基数标签。

### 5.2 受控企业资料来源

- 已实现 SharePoint、Confluence、Notion、挂载目录和 S3/MinIO 的模块内窄适配器，不抽象成通用连接器平台。
- adapter 保留源 ID、版本/ETag、更新时间、内容哈希和可见范围。
- 同步任务幂等、可重试、可观测；源端删除默认停用本地版本，不静默保留可检索旧内容。
- 未取得或无法映射源端 ACL 时不得把资料默认扩大为全员可见。
- 每个外部来源只有在部署方真实租户中完成权限、增量、删除和限流验证后，才允许标记为生产已验证。

### 5.3 企业身份和跨模块闭环

- 增加可替换的 OIDC/SSO 登录 adapter，把企业组映射到现有业务角色和知识可见范围。
- Support 先做真实工单只读导入和“人工确认后回写草稿”，不自动发送、退款或改账号。
- Report 复用 Data 查询结果、Support 质量统计和 Knowledge 维护项生成客服质量周报，不引入工作流引擎。

## 6. 2.2 发布强制门禁

1. V22/V23 必须通过空库和历史库升级，反馈与处置外键、约束、索引、幂等更新、并发冲突和重新入队均有 PostgreSQL 集成证据。
2. 反馈必须只能绑定本人问答；质量队列只能由 Admin/Reviewer 读取。
3. `public-demo` 不接收真实反馈或企业资料，不泄露内部质量队列。
4. 固定评测、全量 Maven/SBOM、前端和 Shell 静态门禁全部通过。
5. PostgreSQL/pgvector、MySQL、备份恢复、容量、真实模型和远端供应链门禁沿用 2.1 标准。
6. 首个企业 adapter 必须完成真实系统沙箱验证后才可写成“已支持”，仅有接口或 mock 不算完成。
