# Spring AI Business Copilot 项目规划

## 1. 项目总定位

Spring AI Business Copilot 是一个面向个人开发者、中小团队和企业内部系统的 Java AI 业务智能助手套件。

项目不是框架，不是玩具 demo，也不是单点聊天机器人。它要提供一组可以直接运行、学习、改造和接入真实业务系统的 Spring AI 应用模块。

核心定位：

> 用 Java / Spring AI 做一套中小团队能直接参考和改造的 AI 业务项目。

英文表达：

> Ready-to-run Spring AI business copilot modules for real-world internal systems.

## 2. 为什么做 Business 方向

单纯做工具项目更容易冲 star，但 Business 方向更适合长期提升：

- 能提升完整 AI 应用开发能力。
- 能展示 Java/Spring Boot 工程能力。
- 能沉淀可交付的业务项目经验。
- 能为中小团队和企业内部系统提供参考。
- 能形成“Java AI 业务落地”的个人品牌。

大量团队真正缺的不是“如何调模型”，而是：

- AI 功能如何嵌入业务流程。
- prompt、tool、guardrails、审计、异常处理如何组织。
- 一个 Spring AI 项目应该怎么分层。
- 如何从 demo 走向可交付系统。

## 3. 总目标

构建一个模块化 Java AI 业务套件。

总目标：

1. 提供多个常见业务模块。
2. 每个模块都能独立运行和演示。
3. 多个模块共享一套平台能力。
4. 以 Data Copilot 为第一版起点，逐步交付五个完整业务模块。
5. 项目能被个人开发者、中小团队直接 clone 后改造。

推荐模块：

- Data Copilot：数据库查询助手
- Knowledge Copilot：企业知识库助手
- Support Copilot：智能客服助手
- Resume Copilot：简历筛选助手
- Report Copilot：报表和周报助手

共享平台能力：

- ai-core：模型调用、prompt、tool calling
- ai-guardrails：安全边界、SQL 校验、敏感信息处理
- ai-tool-audit：工具调用审计
- common-web：API 响应、异常、分页
- common-security：当前操作者、角色读取、对象访问策略和 token 摘要辅助；不承载 IAM
- document-processing：有界 TXT/Markdown/PDF/DOCX 文本提取；不承担通用文件存储

## 4. 目标用户

### 4.1 个人 Java 开发者

需求：

- 想学习 Spring AI，但不想只看 hello world。
- 想看一个完整业务系统怎么接入 AI。
- 想把项目作为作品、面试、文章、视频素材。

项目价值：

- 有完整代码结构。
- 有真实业务模块。
- 有 prompt、tool、guardrails、审计等工程实践。

### 4.2 中小团队

需求：

- 想快速验证 AI 能否进入内部业务。
- 缺少完整 AI 应用模板。
- 不想一开始就接复杂平台。

项目价值：

- 可以直接启动。
- 可以替换模型和数据库。
- 可以基于模块改造成内部系统。

### 4.3 企业内部应用团队

需求：

- 需要 AI 查询数据、客服辅助、简历筛选、知识库问答等能力。
- 需要安全、审计、权限和可解释结果。

项目价值：

- 提供可参考的工程分层。
- 提供基础 guardrails。
- 提供业务模块样板。

## 5. 总体架构

推荐目录：

```text
spring-ai-business-copilot/
  app/
    business-copilot-app/
  platform/
    ai-core/
    ai-guardrails/
    ai-tool-audit/
    common-web/
    common-security/
    document-processing/
  modules/
    data-copilot/
    knowledge-copilot/
    support-copilot/
    resume-copilot/
    report-copilot/
  examples/
    docker-compose.yml
    sample-data/
  docs/
```

架构原则：

- 当前已实现 Data、Knowledge、Support、Report、Resume 五个 Copilot。
- 平台能力必须被业务模块真实使用，不能空转。
- 不提前做复杂平台化能力。
- 每个业务模块必须有与风险匹配的 audit 和 guardrails，不强制共用同一套业务审计表。
- 持久层统一使用模块内显式 Spring JDBC Repository；动态 SQL、schema metadata、条件状态更新、批量写入和 pgvector 查询分别保持清晰边界。
- Spring AI 固定使用 2.0.x，并优先使用原生结构化输出和 Jackson 3。

## 6. 分阶段目标

### 阶段 0：项目地基

目标：

确定项目定位、模块规划、文档规则和第一版边界。

交付物：

- README 中英文入口
- AGENTS.md
- 项目规划
- 模块规划
- 基础架构说明

### 阶段 1：Data Copilot 最小可用版

目标：

完成数据库查询助手闭环。

交付物：

- 示例数据库
- schema 读取和描述
- 自然语言转 SQL
- SQL 只读校验
- SQL 执行前确认
- 查询执行
- 结果表格返回
- AI 结果解释
- 查询审计日志
- Docker Compose 启动

成功效果：

用户可以问：

```text
上个月销售额是多少？
退款率最高的商品有哪些？
本周新增用户数量是多少？
```

系统返回：

- 生成的 SQL
- 安全校验结果
- 查询结果
- AI 业务解释
- 审计记录

### 阶段 2：平台能力沉淀

目标：

从 Data Copilot 中沉淀出可复用能力。

交付物：

- ai-core
- ai-guardrails
- ai-tool-audit
- prompt 模板管理
- 基础测试

### 阶段 3：Knowledge Copilot

目标：

增加企业知识库助手，作为 Data Copilot 之后的第二个业务模块。

能力：

- 文档上传和解析
- 文档分片和向量化
- 基于知识库检索问答
- 答案来源引用
- 无依据时拒答
- 知识问答审计

选择原因：

- 痛点普遍，几乎所有团队都有制度、产品、运维、交付、售后等内部文档难查的问题。
- 可独立 demo，不强依赖第三方客服系统、招聘系统或任务系统。
- 能自然沉淀文档解析、embedding、retrieval、citation、AI 回答 guardrails 等平台能力。
- 与 Data Copilot 形成互补：一个查结构化数据，一个查非结构化知识。

### 阶段 4：Support Copilot

目标：

增加智能客服助手，作为 Knowledge Copilot 之后的第三个业务模块。

能力：

- 工单内容解析
- 工单分类
- 情绪和紧急程度识别
- 知识库依据检索
- 客服回复草稿
- 转人工建议
- 回复审计

选择原因：

- 客服回复效率和质量是中小团队的高频痛点。
- 已有 Knowledge Copilot 后，可以基于 FAQ、产品手册、退款政策等知识生成有依据的回复。
- MVP 可以用示例工单独立演示，不必接入真实客服系统。
- 业务风险可通过”只生成草稿、不自动发送、人工确认”控制。

**V3 已实现完成（2026-07）。** Support Copilot 作为第三模块已实现：工单分类、情绪/紧急程度识别、知识库检索适配、回复草稿生成与 guardrail、人工确认机制和审计日志。

### 阶段 4.5：框架加固

目标：

在继续增加业务模块前，修复当前框架一致性和持久层重复问题。

能力：

- 保持 Spring Boot 4.1.0 和 Spring AI 2.0.0。
- 使用 Spring AI 原生结构化输出和 schema validation。
- 统一 Jackson 3，删除 Jackson 2/3 双栈。
- 正确实现业务模块自动配置和 enabled 开关。
- 稳定聚合 CRUD 优先使用模块内显式 JDBC Repository，避免模块依赖宿主根包扫描。
- 保留 Data 动态 SQL、schema metadata 和 pgvector JDBC。
- 增加 PostgreSQL/Testcontainers 集成测试。
- 明确关键审计的 fail-open/fail-closed 策略。

当前进度（2026-07-29）：`v2.0.0` 已发布，2.1 已形成质量、生产韧性和公网产品化基线。`2.2.0` 已完成五模块企业扩展、V22–V28 迁移、PostgreSQL 升级验证和正式版收口；外部 SaaS/ATS/对象存储适配器继续受部署方真实沙箱联调门禁约束，不增加第六模块。

2.0 发布前的本地全量验证、容器、迁移和五模块真实模型证据保留在历史审核中。2.1 必须重新完成全量 Maven、固定评测、PostgreSQL/MySQL、Shell/前端静态检查和远端供应链门禁；容量与恢复脚本必须在可控环境实跑后才能声明通过。

当前完整审核和 P0/P1 结论以 `docs/current-project-audit-2026-07-16.md` 为准。

### 阶段 5：Report Copilot

目标：

增加报表和周报助手，作为第四个业务模块。

能力：

- 业务指标快照
- 任务和会议记录归一化
- 有来源的结构化周报
- 事实、行动项和 AI 建议分离
- 人工确认
- Markdown 导出
- 报告审计

选择原因：

- 可直接复用 Data Copilot 的只读数据原则、Knowledge Copilot 的引用能力和 Support Copilot 的草稿确认模式。
- 周报和经营简报是团队高频工作，可用虚构指标和记录形成完整演示闭环。
- 不直接参与人事决策，风险低于 Resume Copilot。

**V4 已完成规划并实现第六切片（2026-07-11）。** 已落地模块骨架、Flyway 表结构、示例来源预览、报告请求校验和客户端来源归一化、Spring AI 结构化报告生成、草稿持久化/确认/取消、确认后的 Markdown 导出、共享工作台，以及不可信输出的 NEEDS_REVIEW 持久化与导出审计；详细范围见 `docs/report-copilot.md`。

当前边界：报告生成、不可变来源快照、来源新鲜度、CSV/JSON 导入、草稿确认/取消和 Markdown/HTML 导出闭环已成立。下一阶段不继续增加报表类型；真实第三方任务/会议 adapter 只有在第二个业务场景验证后再考虑沉淀。

### 阶段 6：Resume Copilot

目标：

增加证据化简历评估助手，作为第五个业务模块。

能力：

- JD 标准解析和人工确认
- 简历脱敏和受保护属性移除
- 技能与经历证据抽取
- 逐条标准匹配
- 证据缺口和面试核验问题
- 人工复核
- 招聘辅助审计

安全边界：

- 不生成候选人总分、排名、通过概率。
- 不自动录用、淘汰或改变招聘流程。
- 不根据受保护属性、学校品牌、公司品牌或职业空窗做决策。

**V5 已完成实现（2026-07-11）。** 已落地 JD 标准解析与确认、简历脱敏、确定性 evidenceId、证据化匹配、招聘合规 guardrails、人工复核/取消、审计、示例数据和共享工作台。详细范围见 `docs/resume-copilot.md`。

### 阶段 7：2.0 现有模块升级与产品化收口

目标：

在不增加第六个业务模块的前提下，将五个已实现 Copilot 从演示闭环升级为可被中小团队参考和改造的业务样板。

已完成交付物：

- Data：schema/function/LIMIT/JDBC 资源边界、最小权限 reader、可信 candidate 和 PostgreSQL/MySQL 外部只读查询目标。
- Knowledge：异步索引、TXT/Markdown/PDF/DOCX、文档版本、混合检索和 grounded citation 评测。
- Support：确定性分类、条件状态机、知识版本、人工编辑/接受/拒绝反馈。
- Report：不可变来源快照、来源新鲜度、CSV/JSON 导入、模板版本和可信 Markdown/HTML 导出。
- Resume：文档接入、JD 版本、隐私生命周期、人工纠正、主动删除和合规评测。
- 交付底座：非 root 容器、Compose 只读文件系统、SBOM、依赖审查、Trivy 和 Dependabot。

合并门槛：

- 远端 PR 的 Maven、PostgreSQL、MySQL 5.7/8.4、SBOM、依赖审查和容器扫描全部通过。
- 使用真实 chat/embedding 完成五模块 release smoke。
- 任何门禁失败先在 2.0 分支修正，不直接合并 `main`。

2026-07-18 本地收口结果：

- 五模块中文真实模型 smoke 全部通过。
- app/postgres 容器 healthy，运行用户为非 root UID 10001。
- 空库和历史库迁移到 V18 成功，用户已有 Knowledge 文档/分块/向量状态保留。
- 浏览器完成登录、Data 示例 SQL 生成、安全校验、人工确认、5 行结果和中文解释。
- 当前定位是企业导向的可交付业务样板，不宣传为已经具备多租户、企业 IAM、HA 和 SLA 的通用企业平台。

技术决策：平台数据库继续使用 PostgreSQL 16 + pgvector，不整体切换到 MySQL；MySQL 作为 Data Copilot 的可选业务查询目标提供方言适配。

2.0 的当前执行门槛见 `docs/upgrade-roadmap.md`，完整审核证据见 `docs/current-project-audit-2026-07-16.md`。已经采纳的数据模型和安全边界可在 ADR 中追溯。本文件继续作为项目级范围和阶段顺序的权威入口。

## 7. 第一版详细目标：Data Copilot

Data Copilot 是第一版唯一主模块。

### 7.1 业务目标

让业务人员通过自然语言查询数据库，并获得安全、可解释的结果。

示例：

```text
查询上个月销售额最高的 10 个商品。
分析本周新用户增长趋势。
找出退款率最高的商品分类。
统计不同渠道的订单转化率。
```

### 7.2 核心流程

```text
用户提问
  -> 获取可访问 schema
  -> 构建 prompt
  -> LLM 生成 SQL
  -> SQL guardrails 校验
  -> 用户确认
  -> 执行只读查询
  -> 结果脱敏
  -> AI 解释结果
  -> 记录审计日志
```

### 7.3 安全边界

必须实现：

- 只允许 `select`。
- 拦截 DDL 和 DML。
- 限制返回行数。
- 限制查询超时。
- 禁止访问未授权表。
- 敏感字段脱敏。
- 所有查询记录审计日志。

### 7.4 示例业务库

第一版建议内置电商数据：

- users
- products
- orders
- order_items
- payments
- refunds
- channels

原因：

- 数据结构容易理解。
- 查询场景丰富。
- 适合展示销售额、用户增长、退款率、渠道效果。

## 8. 模块目标概览

### 8.1 Data Copilot：数据库查询助手

总目标：

让非技术人员通过自然语言安全查询业务数据库。

重点能力：

- Text-to-SQL
- SQL Guardrails
- 查询审计
- 结果解释

### 8.2 Knowledge Copilot：企业知识库助手

总目标：

帮助团队基于内部文档问答，并给出引用来源。

重点能力：

- 文档导入
- RAG 检索
- 来源引用
- 无依据拒答
- 知识问答审计

### 8.3 Support Copilot：智能客服助手

总目标：

帮助客服团队基于知识库依据生成可确认的回复草稿，并识别高风险工单。

重点能力：

- 工单分类
- 情绪和紧急程度识别
- 知识库依据检索
- 回复草稿
- 转人工判断
- 回复审计

### 8.4 Report Copilot：报表和周报助手

总目标：

帮助团队基于可信指标、任务和会议记录生成有来源、可确认的报告草稿。

重点能力：

- 周报和经营简报生成
- 指标、任务和会议记录来源引用
- 事实与 AI 建议分离
- 人工确认
- Markdown 导出
- 报告审计

### 8.5 HR Copilot：招聘与员工服务智能助手

总目标：

把内部 Resume 能力作为招聘辅助流程，并复用 Knowledge 的 `HR_POLICY` 分类提供员工制度问答。招聘流程不打分，重点输出岗位匹配证据、证据缺口、待核实问题和面试题。

重点能力：

- JD 标准确认
- 简历隐私与受保护属性处理
- 经历证据抽取
- 逐条匹配和信息缺口
- 面试核验问题
- 人工复核和审计

## 9. 不做什么

第一版不做：

- 多业务模块同时实现。
- 商业 BI 平台。
- 复杂低代码平台。
- 企业级权限系统。
- 多租户 SaaS。
- 私有模型管理平台。

项目必须避免变成“大而全但都不完整”的半成品。

## 10. 成功指标

第一版成功指标：

- 新用户 10 分钟内能跑通 Data Copilot。
- 内置 demo 数据可直接查询。
- 至少支持 10 个高质量业务问题。
- 所有 SQL 都经过只读校验。
- 查询结果可解释。
- 查询日志可审计。
- README 能让中小团队理解如何改造成自己的系统。

## 11. 2.1 产品化与长期公网体验（2026-07-24）

项目采用“业务产品层＋管理技术层”，不新增第六个模块：

- 业务层：企业知识助手、客服工作台、HR Copilot、数据分析助手、报告生成助手。
- 管理层：模型、索引、Prompt/规则哈希、用量成本、限流熔断、可见范围和脱敏审计摘要。
- 运行模式：`development`、`self-hosted`、`public-demo`，同一套代码不分叉。

`public-demo` 已实现 15 个服务端版本化场景。普通请求只提交 `scenarioId + userInput`；完整简历、制度正文、报告来源和数据库范围只在服务端加载。选择场景只填充页面，用户确认后才调用模型。

长期公网边界：

- 每客户端每日 20 次业务操作、全站每日 500 次模型调用、最大并发 4。
- 五模块自由输入均先经过服务端敏感信息、密钥、个人信息、长度和提示注入检查。
- 临时数据保留 24 小时、操作记录 7 天、用量聚合 30 天；`systemManaged` 永久保留。
- 模型异常时只提供单独标记为 `PREGENERATED` 的示例结果，不伪装实时调用。
- 私有 Admin 可幂等初始化；恢复使用一次性 token 和固定确认文案，且只影响 demo 临时数据。

发布门禁与 Railway 操作顺序见 `docs/public-demo-deployment.md`。

## 12. 2.2 企业质量闭环与接入（2026-07-28）

2.2 不扩展模块数量，而是让现有模块接近企业真实使用：

- Knowledge：稳定回答 ID、用户反馈、无依据/拒绝/负反馈质量队列、后续人工处置和评测集沉淀。
- 企业资料：已提供 SharePoint、Confluence、Notion、挂载目录和 S3/MinIO 的窄适配器；每个来源都必须分别完成幂等同步、变更检测、删除传播、ACL 失败关闭和真实沙箱验收。
- 企业身份：使用 OIDC/SSO adapter 消费身份和组声明，不建设 IAM 平台。
- Support/Report：真实工单只读导入、人工确认后回写草稿、客服质量周报。
- Data/HR：继续纵向增强业务语义和证据复核，不引入 BI、批量排名或自动决策。

第一切片使用 V22 保存操作者绑定的知识反馈，V23 保存人工处置；V24–V28 分别承载 Data 治理、Knowledge 来源、Support 外部工单、Report 交付和 HR 协同。质量队列和低基数统计仅允许 Admin/Reviewer 查看。处置使用单调递增的问题修订号做乐观并发保护，反馈更新后自动重新入队。完整范围与发布门禁见 `docs/upgrade-roadmap.md`。
