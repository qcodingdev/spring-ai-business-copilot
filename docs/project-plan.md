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
4. 第一版只做一个模块，但架构能承接后续扩展。
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
- common-security：用户、角色、权限，后续增强

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

- 第一版只实现 Data Copilot，但目录和边界允许后续模块加入。
- 平台能力必须被业务模块真实使用，不能空转。
- 不提前做复杂平台化能力。
- 所有业务模块必须统一接入 audit 和 guardrails。

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

### 阶段 5：Resume Copilot

目标：

增加简历筛选助手。

能力：

- JD 解析
- 简历解析
- 技能匹配
- 候选人评分
- 风险点总结
- 面试题生成

### 阶段 6：Report Copilot

目标：

增加报表和周报助手。

能力：

- 会议纪要摘要
- 任务提取
- 周报生成
- 数据摘要
- Markdown 导出
- Word 导出，后续考虑

### 阶段 7：项目产品化打磨

目标：

让项目成为真正可被中小团队参考的开源样板。

交付物：

- 完整 demo 数据
- 示例截图
- 部署文档
- 模块扩展指南
- 贡献指南

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

### 8.4 Resume Copilot：简历筛选助手

总目标：

帮助招聘团队快速理解候选人与 JD 的匹配度。

重点能力：

- JD 解析
- 简历结构化
- 技能匹配
- 候选人风险点
- 面试题生成

### 8.5 Report Copilot：报表和周报助手

总目标：

帮助团队从数据、任务和会议记录中生成报告。

重点能力：

- 周报生成
- 会议纪要摘要
- 任务提取
- 数据摘要
- Markdown / Word 导出

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
