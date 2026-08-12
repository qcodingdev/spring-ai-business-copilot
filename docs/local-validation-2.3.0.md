# 2.3.0-SNAPSHOT 完整本地验证报告

> 最近验证日期：2026-08-12
> 分支：`2.3.0-SNAPSHOT`
> 版本：全部 13 个 Maven POM 为 `2.3.0-SNAPSHOT`  
> Git 边界：只发布 Snapshot 功能分支；不合并主干，不创建正式标签或 Release

## 1. 本地交付结论

2.3.0 的本地开发范围已完成：Vue 3 + TypeScript + Vite 取代已迁移的
Thymeleaf/原生 JavaScript，继续由 Spring Boot 同域、同镜像交付；登录、公开预览、
工作台、Admin 和五个 Copilot 支持 `zh-CN`/`en-US`；企业 API 具备受控 UI；
外部连接统一经过 HTTPS、allowlist、DNS/IP、重定向、超时、大小、密钥引用和审计边界。

未增加第六个 Copilot、微服务、多租户、复杂 IAM、浏览器 Agent 或跨域独立部署。

## 2. 已执行门禁

| 范围 | 实际命令或方式 | 结果 |
| --- | --- | --- |
| 前端依赖与唯一 lockfile | `npm ci` | 通过，304 个包 |
| Node/npm | Node `22.22.3`、npm `10.9.8` | 通过，Maven 与本地版本一致 |
| 类型/Lint/单测/构建 | `npm run check` | 通过；3 个 Vitest 文件、6 个测试 |
| 双语言与组件行为 | i18n key parity、非法值回退、Intl、API Client 单测 | 通过 |
| E2E | `npm run test:e2e` | Chromium 桌面/手机共 20 个用例通过 |
| 打包后 E2E | `E2E_BASE_URL=http://127.0.0.1:58080 npm run test:e2e` | 真实 Spring 登录后 20 个用例通过 |
| 无障碍/响应式 | Playwright + axe、桌面/Pixel 7、横向溢出与焦点检查 | 无 serious/critical 违规 |
| 固定评测 | `./scripts/check-evaluation-datasets.sh` | 五模块 67 条数据集门禁通过 |
| Shell | `bash -n scripts/*.sh` 与 `sh -n scripts/*.sh` | 通过 |
| Compose | `docker compose ... config --quiet` | 通过 |
| Maven/SBOM | `./mvnw --batch-mode --no-transfer-progress verify -Psbom` | 13 模块全部成功；生成 CycloneDX XML/JSON |
| PostgreSQL/pgvector | `PostgresPgvectorIntegrationTest` | PostgreSQL 16、pgvector、空库 V1→V29 通过 |
| 2.2.1 升级 | V28 写入既有审计记录后迁移到 V29 | 记录保留、五张审计表 locale 补齐 |
| MySQL | Testcontainers `mysql:5.7` 与 `mysql:8.4` | 只读查询、元数据和脱敏通过 |
| Docker build | `docker build -t spring-ai-business-copilot:2.3.0-snapshot .` | 通过；构建上下文约 154 KB |
| 镜像运行时 | image inspect + Compose | Java 21.0.11、UID/GID 10001、无 Node、健康检查 |
| 容器加固 | self-hosted Compose | 只读根文件系统、`cap_drop: ALL`、no-new-privileges |
| 实际 HTTP | `scripts/smoke-test.sh` | 健康、CSRF、登录、工作台、Data schema 通过 |
| public-demo | 独立 Compose + Admin 初始化 + 场景 API | 15 个虚构场景；预生成结果标记正确；原始模块 API 403 |
| 无模型降级 | public-demo 实时场景请求 | HTTP 503 + 稳定 `BIZ_0100`，未伪造实时结果 |
| 真实模型 | `scripts/release-ai-smoke-test.sh` | Data、Knowledge/Embedding、Support、Report、HR 全部通过 |
| 真实英文输出 | Data 请求发送 `Accept-Language: en-US` | HTTP 200；用户可见摘要/假设/警告无中文字符 |
| Git 空白错误 | `git diff --check` | 通过 |

真实模型测试只从已存在且被 Git 忽略的 `examples/.env` 注入密钥；验证过程未输出、
复制或提交密钥。测试数据均为虚构内容。

## 3. 关键安全证据

- `GET /api/session` 统一初始化 CSRF Cookie；前端 POST/PUT/PATCH/DELETE 发送
  `X-XSRF-TOKEN`，401/403/409/5xx 只按稳定 `errorCode` 和 `requestId` 展示。
- locale 进入低基数请求上下文和五模块审计列，不进入指标高基数标签，也不记录正文。
- AI 输出语言由集中 Prompt 上下文控制，并经过确定性语言校验；不符合时仅安全重试一次。
- 外部连接默认 HTTPS 和域名 allowlist，DNS 解析后阻断 loopback、link-local、私网、
  组播、云元数据、CGNAT 与 IPv6 ULA；禁用自动重定向，限制连接/读取/任务超时、
  响应字节、页数、条数和 JSON 深度。
- 外部密钥只保存环境变量引用；缺失密钥失败关闭；页面、API、日志不返回秘密。
- HR 保持证据化人工评估，不增加总分、排名、推荐概率、自动录用或自动淘汰。
- public-demo 默认拒绝原始业务 API；无模型时只允许明确标记的预生成结果，不冒充实时 AI。

## 4. 尚未验证和发布前事项

以下项目需要远端或正式环境，当前不能声明通过：

- GitHub/Gitee 远端 CI、dependency review 和远端安全门禁。
- Trivy/同类容器扫描：本机未安装扫描器；SBOM 已生成，但不能替代镜像漏洞扫描。
- 真实 Jira/Notion/ServiceNow/ATS 等外部厂商端点：未配置可安全使用的测试租户。
- 正式 `prod` 环境启动、域名/TLS、备份恢复和容量压测；本地仅通过生产配置校验测试。
- 正式版 `2.3.0`、发布提交、双远端推送、标签和 Release；均需用户另行授权。

发布授权前必须继续保持 `2.3.0-SNAPSHOT`，重新执行正式版本门禁后才能创建 `v2.3.0`。

## 5. 2026-08-06 全项目闭环复核补充

本次在不改变 `2.3.0-SNAPSHOT` 发布边界的前提下，重新检查前端、API、数据库、
模型调用与五模块状态流转。确认并修复了以下可复现问题：

- Data 快捷问题与示例 schema 不一致；已改为可通过白名单的已完成订单问题，并补齐
  `orders`/`refunds` 字段语义和 schema Prompt 容量。
- Knowledge 引用对象直接显示 JSON；已改为“来源/分片 + 摘要”，员工服务统一使用
  `HR_POLICY` 分类并复用知识答案、引用和反馈闭环。
- Support 本地工单确认后错误展示外部回写入口；已增加服务端资格查询，仅外部导入工单
  进入一次性 token 回写流程，已确认/取消草稿的编辑与决策按钮同步禁用。
- Report 导航切换后没有及时加载 Data 交接，证据校验失败仍会提前消费交接；已修复
  Tab 生命周期，并将单行 Data/Support 聚合规范化为精确指标证据，只有 `DRAFTED`
  才消费交接。无效 Cron 或时区现在返回稳定的 `VALIDATION_ERROR`，不再泄漏为 500。
- HR 标准提取实际返回 `CRITERIA_DRAFTED`，前端却不可编辑；已统一状态判断，并在
  Assessment/Authorization/Interview/Onboarding Tab 进入时加载对应数据。
- Admin 最近任务 SQL 字段名与前端 camelCase 契约不一致；已在查询层显式别名。

浏览器真实环境已完成登录，以及 Data SQL 候选/人工确认/查询结果、Knowledge 初始化/
带引用问答/反馈、Support 编辑/确认与终态禁用、Report 交接/校验失败反馈/定时任务、
HR 岗位草稿/标准提取、员工知识问答和 Admin 诊断页面检查。修复后的本地自动化门禁为：

- Node `22.22.3`：`npm run check` 通过（5 个 Vitest 文件、9 个测试和生产构建）。
- Chromium：开发服务器与打包后 `http://127.0.0.1:8080` 各完成 32/32，通过桌面/
  移动端、双语、五模块主流程、人工确认、无横向溢出及 serious/critical 无障碍检查。
- Java：`./mvnw --batch-mode --no-transfer-progress verify -Psbom` 13 模块全部
  `SUCCESS`；MySQL 8.4、PostgreSQL 16/pgvector、V1→V29 与 v2.2.1→V29 升级、
  Spring Security 和 199 组件 CycloneDX XML/JSON SBOM 均通过。
- 运行态：Compose 的 app/PostgreSQL 健康，`scripts/smoke-test.sh` 通过健康、CSRF、
  登录、同源工作台和 Data schema；最终容器启动后的日志未发现 WARN/ERROR。

真实模型的首次五模块浏览器链路使用虚构演示数据完成；修复后的再次外部模型调用因无法
确认具体数据接收方授权而未绕过安全审查，
对应分支由 Mock/单元/集成测试回归。远端 CI、镜像漏洞扫描、厂商沙箱和生产环境仍不在
本地验收结论内。

## 6. 2026-08-09 人工复核与报告入口复验

本轮针对客服人工复核、知识质量复核、报告来源入口和招聘二级导航继续补齐闭环：

- Support 人工复核队列改为可筛选业务待办，展示风险、分类、情绪、紧急度、证据和草稿；
  支持重新签发绑定凭证、修订、确认/驳回，以及记录客户回复或人工渠道已完成。
- Knowledge 质量复核改为结构化判断，持久化证据评估、答案评估、后续动作、处置结论和
  复核说明；队列同时展示脱敏回答预览、检索证据和实际引用，历史拒答状态已本地化。
- Report 把 Data 结果交接放在生成页首位，选择结果或快捷开始时自动填写标题和来源；
  同时保留手工输入与受限 CSV/JSON 上传，不选择 Data 也能完成生成流程。
- HR 左侧二级菜单改为紧凑分组导航，招聘协同与员工服务的层级、选中态和响应式布局统一。

最终回归证据：

- Node `22.22.3`：`npm run check` 通过，包含类型检查、零警告 Lint、5 个 Vitest 文件/
  9 个测试和生产构建。
- Playwright Chromium：桌面与移动端共 38/38 通过，覆盖四项新增流程、中英文界面、
  五模块主流程、无横向溢出及 serious/critical 无障碍检查。
- Maven：`./mvnw --batch-mode --no-transfer-progress verify -Psbom` 的 13 模块 Reactor
  全部 `SUCCESS`；MySQL 8.4 与 PostgreSQL 16/pgvector 集成测试无跳过，空库 V1→V30、
  V7→V30 和 v2.2.1 V28→V30 升级均保留既有数据，CycloneDX SBOM 包含 199 个组件。
- 运行态：最终镜像 `sha256:6fe44832716ba934fc8f19dc24da55fdb627878e29ac0244eb87ed3398ae1e6a`
  已由 Compose 重建；app/PostgreSQL 均为 `healthy`，Flyway 当前版本为 V30。最终镜像上再次
  执行桌面/移动端 38/38 E2E，浏览器控制台无错误，应用日志未发现 WARN、ERROR 或 5xx。

最终闭环复查还完成了 25 个业务子页面与系统管理四个子菜单的浏览器遍历，并在本地虚构
数据上实际执行三笔可审计状态变更：Data 查询结果创建 `READY` 报告交接并在报告页自动
填充标题和来源；Knowledge 将一笔证据不足问题持久化为 `KNOWLEDGE_UPDATE_REQUIRED`，
同时保存证据/答案评估和更新知识动作；Support 将 `DEMO-SUPPORT-002` 经二次确认从
`NEEDS_HUMAN` 流转到 `CLOSED`，审计事件记录操作者 `admin`。另外去除了非主任务页外层
标题与内部面板的重复标题，保留说明、刷新动作和完整内容层级。

本轮没有再次调用外部模型，也未向外部系统写入数据：真实模型脚本会使用本地密钥并向
外部供应商发送五模块虚构请求，在未明确获知供应商/数据接收方授权前未绕过安全门禁。
报告生成模型和企业连接仍需部署方提供已授权的密钥/沙箱后做真实供应商验收；远端 CI、
镜像漏洞扫描和生产环境结论保持未验证。

## 7. 2026-08-12 Snapshot 分支发布门禁

本次交付继续保持 `2.3.0-SNAPSHOT`，只发布
`2.3.0-SNAPSHOT` 分支，不合并 `main`，不创建 `v2.3.0`
标签或正式 Release。中英文 README 已明确此边界，并从当前运行实例重新生成首屏动态 GIF
以及 Data、Knowledge、Support、Report、HR 五张业务页面图；图片只包含虚构演示数据。

发布提交前重新执行的门禁如下：

- Node `22.22.3`：类型检查、零警告 Lint、5 个 Vitest 文件/9 个测试和生产构建通过。
- Playwright：最终 Compose 应用上桌面/移动端 38/38 通过；标准桌面与 Pixel 7 工作台图
  重新生成，五张模块业务图完成逐张视觉检查。
- Maven：`verify -Psbom` 的 13 模块全部 `SUCCESS`，MySQL 8.4、PostgreSQL 16/pgvector、
  Flyway V1→V30、V7→V30、V28→V30 和包含 199 个组件的 CycloneDX SBOM 均通过。
- 五模块固定评测 67 条、Shell 语法、Compose 配置、Git 空白检查全部通过。
- 运行态 smoke 通过健康、CSRF、登录、认证工作台和 Data schema；app 与 PostgreSQL
  容器均健康。

外部真实模型、真实 Jira/Notion/ServiceNow/ATS 租户、镜像漏洞扫描和生产环境仍不属于
本次 Snapshot 分支发布声明；稳定后冻结正式 `2.3.0` 时应重新完成这些正式发布门禁。
