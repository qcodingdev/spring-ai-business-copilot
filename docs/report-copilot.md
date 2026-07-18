# Report Copilot 模块规划

> **V4 规划完成（2026-07）。** 本文档定义第四个业务模块的产品范围、业务闭环、安全边界和验收标准。

> **实现进度（2026-07-16）：** 已完成模块骨架、显式 JDBC Repository、Flyway 表结构、示例来源预览、请求校验与来源归一化、Spring AI 结构化报告生成、草稿持久化/确认/取消、确认后的 Markdown 导出，以及共享工作台入口。有效输出会创建 DRAFTED 草稿；证据校验失败会创建只含确定性复核原因的 NEEDS_REVIEW 草稿，不回显不可信模型正文。owner、摘要 token、过期和条件状态更新已加入；确认不会触发外部发布，Markdown 由服务端渲染并转义模型文本。

## 业务价值

Report Copilot 是报表和周报助手，帮助团队把可信的业务数据、任务进展和会议记录整理为结构化报告草稿。

典型场景：

- 运营负责人生成周度经营简报。
- 项目经理汇总本周完成事项、风险和下周计划。
- 小团队根据销售、退款、用户增长等指标生成管理周报。
- 团队把零散会议记录整理为待办和决策摘要。

核心价值：**减少重复汇总和排版工作，同时保证每个数据结论、完成事项和待办都有来源。**

---

## 为什么作为第四模块

前三个模块已经覆盖结构化数据查询、非结构化知识检索和客服工单辅助。Report Copilot 可以复用这些已验证能力：

- 复用 Data Copilot 的只读数据、安全查询、结果脱敏和审计原则。
- 复用 Knowledge Copilot 的来源引用、无依据拒答和文档片段追踪能力。
- 复用 Support Copilot 的草稿、人工确认、状态流转和业务审计模式。

相比 Resume Copilot，Report Copilot 不直接参与高影响的人事决策，合规风险更低，也更适合作为第四个可交付模块。

---

## MVP 范围

第一版只做“有来源的团队周报和经营简报生成”。

必须实现：

- Spring Boot 后端。
- Spring AI ChatClient 调用。
- 报告类型和统计周期输入。
- 示例业务指标快照。
- 任务进展和会议记录文本输入。
- 输入脱敏和长度限制。
- 来源归一化与证据编号。
- 结构化报告草稿生成。
- 事实、风险、建议和待办明确分区。
- 指标与任务引用校验。
- 无依据内容阻断或标记待确认。
- 人工确认与取消。
- Markdown 预览和导出。
- 报告生成审计日志。
- 简单报告工作台。

暂不实现：

- 不接入真实 Jira、飞书、钉钉、Slack、Notion 或会议系统。
- 不自动发送邮件、群消息或定时发布报告。
- 不做商业 BI 看板和自由拖拽图表。
- 不让 Report Copilot 直接生成或执行任意 SQL。
- 不修改任务状态、负责人或截止时间。
- 不生成 Word、PDF、PPT，后续按真实需求扩展。
- 不做跨团队权限、多租户和审批流平台。
- 不做长期记忆和自动绩效评价。

---

## 核心流程

```text
用户选择报告类型和统计周期
  ↓
选择示例指标快照 + 输入任务进展/会议记录
  ↓
脱敏、长度校验、来源归一化
  ↓
构建带 sourceId 的证据包
  ↓
Prompt + 证据包 → LLM 生成结构化报告草稿
  ↓
Guardrails 校验数字、任务、结论和引用
  ↓
证据充分：DRAFTED
证据不足或存在未引用事实：NEEDS_REVIEW
  ↓
用户确认或取消
  ↓
确认后生成 Markdown 并记录审计
```

---

## 推荐模块结构

```text
modules/report-copilot/
  src/main/java/dev/qcoding/businesscopilot/reportcopilot/
    request/
    source/
    generation/
    guardrail/
    draft/
    export/
    audit/
    web/
```

| 包 | 职责 |
|---|---|
| `request` | 报告请求、类型、周期和输入校验 |
| `source` | 指标、任务、会议记录等来源归一化 |
| `generation` | Prompt 调用和结构化报告生成 |
| `guardrail` | 数字、事实、待办和引用完整性校验 |
| `draft` | 草稿持久化、确认 token 和状态流转 |
| `export` | 服务端 Markdown 渲染与下载 |
| `audit` | 生成、确认、取消和失败审计 |
| `web` | REST API 和报告工作台入口 |

实现前置条件：`docs/current-project-audit-2026-07-16.md` 所列公共底座已经落地。

持久层使用显式 Spring JDBC Repository 管理 report request/source/draft/audit；指标读取通过 `ReportDataProvider`，不能让模型或持久层执行任意 SQL。

---

## 与已有模块的关系

Report Copilot 应通过窄接口复用已有能力，不复制业务模块内部实现。

- 定义 `ReportDataProvider`，读取经过允许的指标快照。
- MVP 可由 app 层提供基于示例数据库的实现。
- 不直接调用 Data Copilot 的自然语言转 SQL 流程，不绕过 SQL 确认和只读规则。
- 定义 `ReportKnowledgeProvider` 作为可选接口，用于读取会议记录或知识片段。
- 无 Knowledge Copilot 时仍可使用用户输入的任务和会议记录完成闭环。
- 当多个模块稳定共用来源模型时，再考虑沉淀平台能力。

---

## 证据模型与事实边界

所有可验证内容必须关联 `sourceId`。

来源类型：

- `METRIC`：名称、值、单位、统计周期和查询时间。
- `TASK`：任务标题、状态、负责人别名和来源说明。
- `MEETING_NOTE`：会议主题、记录片段和记录时间。
- `KNOWLEDGE`：可选知识文档标题、章节和 chunkId。

输出规则：

- 数据指标不得由模型计算或改写原值。
- “已完成”“延期”“阻塞”等任务事实必须有 TASK 来源。
- 行动项必须区分“来源中明确提出”和“AI 建议”。
- AI 建议不得虚构负责人和截止时间。
- 引用缺失时不得把内容呈现为确定事实。
- Markdown 必须由服务端根据结构化对象渲染，不能直接信任模型返回的任意 Markdown。

---

## 状态流转

```text
DRAFTED        证据校验通过，等待人工确认
NEEDS_REVIEW   存在证据不足、未引用事实或待确认项
CONFIRMED      用户已确认，可导出 Markdown
CANCELED       用户取消草稿
FAILED         生成、校验或持久化失败
```

重要约束：

- 模型生成后不能直接进入 `CONFIRMED`。
- 只有服务端生成的确认 token 可以确认草稿。
- `NEEDS_REVIEW` 草稿必须先完成重新生成或显式人工修订，不能直接确认。
- 确认只表示用户认可报告内容，不触发外部发布。
- 确认、取消和失败都必须写审计。

---

## 数据模型草案

### report_requests

| 字段 | 说明 |
|---|---|
| id | 请求 ID |
| report_type | TEAM_WEEKLY / BUSINESS_WEEKLY / PROJECT_STATUS |
| period_start | 统计开始日期 |
| period_end | 统计结束日期 |
| title | 报告标题 |
| created_at | 创建时间 |

### report_sources

| 字段 | 说明 |
|---|---|
| id | 来源 ID |
| request_id | 请求 ID |
| source_type | METRIC / TASK / MEETING_NOTE / KNOWLEDGE |
| source_ref | 外部或内部引用编号 |
| source_title | 来源标题 |
| sanitized_content | 脱敏后的来源内容 |
| source_hash | 内容摘要，用于审计和防篡改比对 |
| created_at | 创建时间 |

### report_drafts

| 字段 | 说明 |
|---|---|
| id | 草稿 ID |
| request_id | 请求 ID |
| structured_content | 结构化报告 JSON |
| cited_source_ids | 引用来源 ID |
| status | DRAFTED / NEEDS_REVIEW / CONFIRMED / CANCELED / FAILED |
| review_reasons | 待复核原因 |
| confirmation_token | 确认 token |
| expires_at | token 过期时间 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

### report_audit_logs

记录 `request_id`、`draft_id`、`event_type`、来源类型与数量、引用来源 ID、模型名、耗时、状态和错误摘要。审计日志不保存未脱敏输入和完整报告正文。

---

## API 草案

Base path: `/api/report-copilot`

### GET /sample-sources

返回可用于演示的指标快照、任务进展和会议记录。

### POST /reports/generate

输入：报告类型、周期、标题、选择的示例指标、任务文本和会议记录。

输出：

- requestId
- draftId
- status
- summary
- sections
- metrics
- risks
- actionItems
- citations
- reviewReasons
- confirmationToken（仅 `DRAFTED` 返回）

### POST /reports/{draftId}/confirm

校验 token 和草稿状态，更新为 `CONFIRMED` 并写审计。

### POST /reports/{draftId}/cancel

取消草稿并写审计。

### GET /reports/{draftId}/markdown

仅允许导出 `CONFIRMED` 草稿，由服务端渲染 Markdown。

---

## Prompt 约束

Prompt 必须集中在：

```text
platform/ai-core/src/main/resources/prompts/report-copilot/
```

模型必须返回结构化 JSON，至少包含：

- `executiveSummary`
- `metricHighlights[]`
- `completedItems[]`
- `risks[]`
- `actionItems[]`
- `suggestions[]`
- `citations[]`

每个事实项必须带 `sourceIds`。建议项必须显式标记 `AI_SUGGESTION`。

---

## Guardrails

- 报告周期最长 366 天，开始日期不得晚于结束日期。
- 限制每类来源数量和单条输入长度。
- 所有文本入模和入库前脱敏。
- 校验引用只指向本次请求的来源。
- 对指标名称、值、单位和周期做确定性比对。
- 禁止模型凭空新增任务、负责人、截止时间和完成状态。
- 未引用事实进入 `NEEDS_REVIEW`。
- 模型输出解析失败时不得保存为可确认草稿。
- 导出内容进行 Markdown 转义，避免注入危险链接或 HTML。
- 不执行模型输出中的 SQL、命令、URL 或工具调用。

---

## 示例数据

全部使用虚构业务数据：

- 电商周度销售、订单、退款和新增用户指标。
- 产品迭代任务进展。
- 项目风险和阻塞项。
- 周会决策和待办记录。

示例数据不得包含真实员工姓名、客户信息、邮箱、手机号、token 或 secret。

---

## 测试要求

- 日期周期和输入长度校验。
- 来源归一化与脱敏。
- 指标值不可被模型篡改。
- 未引用事实进入 `NEEDS_REVIEW`。
- AI 建议与来源行动项明确区分。
- 伪造 sourceId 被拒绝。
- 只有 `DRAFTED` 且 token 有效时可确认。
- `NEEDS_REVIEW` 不可直接确认。
- 只有 `CONFIRMED` 可导出 Markdown。
- 确认、取消和失败审计完整。
- 模型失败或 JSON 错误时错误清晰。
- Data、Knowledge、Support Copilot 回归测试通过。

---

## 验收标准

- 用户可在一个页面选择来源并生成一份完整周报草稿。
- 所有数字、任务事实和会议结论都能定位到来源。
- 无依据内容不会伪装成事实。
- 草稿生成后必须人工确认，系统不自动发布。
- 确认后可下载稳定、可读的 Markdown。
- 全流程有状态、有 guardrails、有审计。
- 模块可独立解释、独立测试、独立演示。
