# Spring AI Business Copilot 2.0 当前项目完整审核

> 首次审核：2026-07-16  
> 最近复核：2026-07-18  
> 审核分支：`feature/v2.0-business-hardening`  
> 版本：`2.0.0-SNAPSHOT`  
> 审核范围：根 Maven 工程、app、platform、五个 Copilot、Flyway V1～V18、权限、测试、工作台、Docker、CI 和文档。

## 1. 总结论

本轮没有增加第六个模块，而是修复了影响真实体验的共性阻断，并完成五模块真实模型闭环。

当前最准确的产品定位是：

> 代码已达到“可信、可运行、可改造、可交付的企业业务样板”，但不应宣传成已经具备多组织 IAM、高可用、容量治理和生产 SLA 的通用企业平台。

本地代码、Testcontainers、真实 Docker、已有数据库升级、中文五模块 AI 冒烟和浏览器主流程均已验证。当前没有已知的 P0/P1 功能阻断；是否提交、推送和发起 2.0 PR 由用户决定。

仍不建议直接合并 `main`：远端 required checks、SBOM/依赖审查、Trivy 和 PR review 属于发布门禁，不能用本地成功替代。

## 2. 本轮发现并修复的关键问题

### 2.1 所有写操作实际返回 403

根因是 Spring Security 7 默认使用 XOR CSRF 请求处理器，而工作台与发布脚本回传页面/Cookie 中的原始 token。登录成功后，所有 POST、PATCH、DELETE 都会在业务 Controller 之前被拒绝。

已修复：

- 显式使用 `CsrfTokenRequestAttributeHandler`，保持 Cookie/页面 token 与请求头一致。
- 增加模拟浏览器渲染 token 回传的回归测试。
- 安全错误响应显式使用 UTF-8，中文不再变成问号。
- 真实容器验证由 HTTP 403 恢复为 HTTP 200。

这也是此前“所有模块看得到、但功能走不完整”的共同根因。

### 2.2 Knowledge 向量维度、文档解析和查询失败

已修复：

- V4 历史迁移恢复为原始 `vector(1536)`，避免修改历史迁移造成 Flyway checksum 冲突。
- V17 将 embedding 列升级为不绑定维度的 `vector`，以后切换模型不再改历史迁移。
- 相似度 SQL 同时校验 `embedding_model` 和 `vector_dims`，历史不同模型/维度的数据会被跳过，不再导致整次查询失败。
- 向量维度不匹配时返回中文配置提示，明确指向 `SPRING_AI_OPENAI_EMBEDDING_DIMENSION`。
- TXT、Markdown、PDF、DOCX 统一通过有界解析器，限制文件大小、提取字符数和 PDF 页数。
- 上传改为持久化异步索引，前端轮询并显示 PENDING/COMPLETED/FAILED 中文状态。
- LLM 只返回 `chunkId`，引用原文由服务端从本次召回 chunk 填充，避免模型改写 excerpt 后被 citation guardrail 拒绝。
- 索引任务查询增加 owner/admin 校验，阻止通过任务 ID 枚举其他用户状态。
- 文档删除后会安全提升上一有效版本，外部 embedding 调用不再包在长数据库事务中。

在保留用户现有数据的情况下，原上传 PDF 当前为 `已完成`、`可检索`；用户原有 1 份文档、5 个 chunk 和 5 个 2560 维向量均保留。加上两次发布 smoke 文档后，当前总计为 3 份文档、7 个 chunk 和 7 个 embedding。

### 2.3 Data 首页示例不能完成

浏览器验收发现“上个月销售额最高的前 5 个客户”会生成 `date_trunc` / `CURRENT_DATE`，而普通函数默认拒绝，导致首页自带示例也无法执行。

已修复：

- 不放宽普通函数白名单。
- SQL prompt v2 注入当前业务日期，要求把“上个月”等相对日期转换为固定 `DATE` 边界。
- 增加固定日期边界 SQL 的 guardrail 测试。
- 浏览器真实验证生成 SQL、规则通过、人工确认、返回 5 行结果和中文 AI 解释。

### 2.4 五模块权限和业务闭环

已修复并验证：

- owner 可以复核本人 Support/Resume 对象；Reviewer 只进入明确复核动作。
- Data execute、Support cancel、Report confirm/cancel、Resume criteria confirm 仅 ADMIN/OPERATOR。
- Knowledge 文档和 Resume 脱敏 submission 删除仅 ADMIN/OPERATOR，并继续执行对象级 owner/admin 校验。
- Support 显示分类原因，允许人工修订后再确认；审计分页字段与后端一致。
- Report 支持 CSV/JSON 预览、生成和 Markdown/HTML 导出，日期不再因 UTC 转换偏移。
- Resume 支持 JD/简历 TXT、Markdown、PDF、DOCX 输入，隐私、状态、匹配结果和复核提示均为中文。

### 2.5 示例数据、配置与凭据安全

已修复：

- V18 新增完全虚构的 120 个客户、30 个商品、720 个订单及明细、退款和营销事件，覆盖 14 个月。
- 当前本地示例库为 125 个客户、727 个订单，足够体验时间范围、聚合、退款和客户分析。
- 不适合自动入库的知识库/JD/简历/报告来源以可下载虚构样例文件提供，由用户主动上传。
- `.env`、`.env.*` 全部忽略，仅保留 `.env.example`；误进入索引的 `examples/.env` 已从 Git 索引移除，本地配置仍保留。
- 已移除对整个 `docs/` 目录的忽略，项目规划、模块说明、审核和发布路线可以进入 2.0 提交。
- `.env.example` 已对齐 Spring AI chat/embedding 的真实环境变量，并说明 Compose 与 IDE 启动差异。
- 生产模式要求独立只读 business reader，避免静默回退到平台数据库。

### 2.6 中文体验

已增加：

- 业务日志、异常、安全响应、校验信息和异步索引状态的中文说明。
- 五模块工作台标题、状态、角色限制、加载、失败、人工复核和下载提示。
- 发布 AI 冒烟脚本及其中的五模块业务数据改为中文。
- 新增或本轮修改的关键安全/业务注释使用中文。

Spring Boot、Tomcat、Hikari、Flyway 等第三方框架自身的启动日志仍按依赖默认语言输出；强行改写第三方日志既不可靠也属于过度设计。

## 3. 当前实测证据

| 检查项 | 2026-07-18 结果 |
|---|---|
| Maven 全量验证 | `./mvnw -q verify` 成功 |
| 测试汇总 | 317 tests，0 failures，0 errors，0 skipped，66 suites |
| PostgreSQL/pgvector | Testcontainers 真实运行成功 |
| Flyway | 空库 V1→V18、历史 V7→V18、现有用户库 V16→V18 成功 |
| MySQL | MySQL 8.4 独立只读查询目标成功 |
| reader 最小权限 | 只能 SELECT 六张示例业务表，不能读审计/其他 Copilot 表，不能 UPDATE |
| Knowledge 混合维度 | 3 维历史向量与 1536 维当前向量并存时检索成功 |
| 真实 Docker | app/postgres 均 healthy；运行用户 UID 10001 |
| 已有数据升级 | 文档、chunk、embedding 和启用状态保留 |
| CycloneDX SBOM | 本地 `-Psbom verify` 成功，生成 JSON/XML |
| 五模块真实模型 smoke | 中文 chat + embedding 全部通过 |
| 浏览器主流程 | 登录、CSRF、Data 示例生成、校验、确认、结果和 AI 解释通过 |
| 前端与 Shell | JavaScript 语法、`bash -n` 通过 |
| Diff | `git diff --check` 通过 |

五模块真实 smoke 覆盖：

1. Data：结构化 SQL、双重 guardrail、确认 token、只读执行。
2. Knowledge：上传、异步 embedding、检索、服务端引用和有依据回答。
3. Support：知识依据、回复草稿和人工确认。
4. Report：证据化生成、确认、Markdown 导出。
5. Resume：标准抽取/确认、证据评估和人工复核。

## 4. 模块成熟度判断

| 模块 | 当前判断 | 企业生产接入前仍需 |
|---|---|---|
| app | 单组织认证、角色边界、中文工作台和诊断链路可用 | 接企业 IdP/SSO、容量与 HA 方案 |
| ai-core | Chat/Embedding、Prompt 身份和模型元数据可用 | provider 配额、熔断和成本告警 |
| ai-guardrails | SQL、敏感信息和输出边界完整且可测试 | 持续扩充对抗集 |
| ai-tool-audit | actor/model/prompt/policy/latency 和保留成立 | 对接企业日志平台和告警 |
| document-processing | 有界 TXT/MD/PDF/DOCX 提取成立 | 特殊扫描件/OCR 需按真实需求另接 |
| Data | 可信只读查询样板，真实浏览器闭环成立 | 企业 schema 适配和查询压测 |
| Knowledge | 中小规模可恢复知识库样板，真实 embedding/RAG 成立 | 大规模语料评测、备份恢复和权限继承 |
| Support | 有状态、可编辑、可确认的客服草稿样板 | 接真实工单系统但保持不自动发送 |
| Report | 有来源、可导入、可导出的报告样板 | 接真实来源 adapter 和业务模板验收 |
| Resume | 单候选人证据复核和隐私生命周期样板 | 法务/HR 合规评审及企业删除策略 |

## 5. 仍然不合理或需要明确的边界

以下不是本轮继续堆功能的理由，但必须在 2.0 说明中如实披露：

1. 当前用户来自内存配置，适合样板和内部单组织部署，不等于企业 IAM。
2. 没有多租户、复杂权限、商业 BI、模型平台、工作流平台和微服务；这是有意控制范围。
3. 没有完成企业生产所需的容量压测、故障演练、备份恢复、RTO/RPO 和 SLA 验收。
4. PDF/DOCX 是文本提取，不含 OCR；扫描版 PDF 不应承诺可解析。
5. 模型输出仍有随机性，真实业务必须保留 guardrail、拒答和人工确认，不能绕过。
6. 示例库和示例文件只用于体验，不应自动混入企业真实数据。

## 6. 2.0 发布决策

当前代码可以作为 2.0 候选进入提交和远端验证，但本轮没有提交、推送或创建 PR。

用户决定推送后，必须依次完成：

1. 审核当前大工作树并精确暂存，排除用户无关文件和本地 `.env`。
2. 创建 2.0 提交并推送 `feature/v2.0-business-hardening`。
3. 新建独立 2.0 PR，不复用旧 1.x PR。
4. 远端 Maven、PostgreSQL/MySQL、固定评测、CycloneDX SBOM 上传、dependency review、Trivy filesystem/image 和非 root 检查全部通过。
5. 复核镜像/依赖漏洞、PR review thread 和发布说明。

上述门禁全部绿色后，才讨论合并 `main` 和正式发布 2.0。
