# Support Copilot 模块文档

> **V3 已实现，2.2 企业扩展已完成（2026-07）。** 以下为模块的完整业务规划、企业接入能力和安全边界。

## 业务价值

Support Copilot 是智能客服助手，帮助客服团队基于知识库依据快速理解工单、生成回复草稿，并识别需要转人工或升级处理的高风险问题。

典型场景：

- 客服面对重复问题时快速生成标准回复
- 售后团队根据退款政策、产品 FAQ 和故障流程回复客户
- SaaS 支持团队识别高优先级故障和情绪激烈工单
- 小团队在没有完整客服系统的情况下，用 AI 辅助统一服务口径

核心价值：**提升客服回复效率和一致性，同时把高风险回复留给人工确认。**

---

## 为什么作为第三模块

Data Copilot 解决结构化数据查询，Knowledge Copilot 解决内部知识问答。Support Copilot 可以站在这两个模块之上，成为第一个更接近一线业务流程的 AI 助手：

- 从 Knowledge Copilot 复用 FAQ、产品手册、退款政策、故障处理流程。
- 从 Data Copilot 的安全思路复用“AI 输出进入业务动作前必须确认”的原则。
- 通过工单分类、回复草稿、转人工建议形成清晰闭环。

相比 Resume Copilot 和 Report Copilot：

- 比 Resume Copilot 更少涉及招聘偏见和自动化决策风险。
- 比 Report Copilot 更容易用示例工单独立演示，不依赖任务系统、会议记录和多数据源。
- 与已完成的 Knowledge Copilot 衔接最自然。

---

## MVP 范围

第一版 Support Copilot 只做“客服工单辅助处理”。

必须实现：

- Spring Boot 后端
- Spring AI ChatClient 调用
- 示例客服工单
- 工单文本输入
- 工单分类
- 情绪识别
- 紧急程度判断
- 知识库依据检索
- 回复草稿生成
- 来源引用
- 高风险回复阻断或转人工
- 回复前人工确认
- 敏感信息脱敏
- 回复审计日志
- 简单客服工作台
- 待复核、高风险、已确认和已取消队列
- 状态、分类、紧急度和风险筛选
- 人工修订差异与最终处理结果

2.2 已实现：

- Jira Service Management、Zendesk、ServiceNow、飞书和企微工单的窄适配器。
- 只读导入客户消息、脱敏客户/订单/服务状态快照、相似历史工单和 SLA 风险。
- 统计草稿采纳、修改采纳、拒绝、转人工及 SLA 风险。
- 只有已确认草稿才能签发一次性回写凭证；回写目标限定为内部备注，失败不自动重试。

继续保持的边界：

- 不自动发送邮件、短信、IM 或客服消息
- 不做通用多渠道会话聚合平台
- 不做客服排班
- 不修改外部工单 SLA 配置或流转状态
- 不做真实订单、退款、赔偿、合同变更操作
- 不做长期对话记忆
- 不做客服绩效考核
- 不做复杂权限系统

---

## 核心流程

```
客服输入工单内容
  ↓
脱敏客户输入
  ↓
AI 分类 + 情绪 + 紧急程度识别
  ↓
检索知识库依据
  ↓
Prompt + 工单 + 知识片段 → LLM 生成回复草稿
  ↓
Guardrails 校验回复风险和引用完整性
  ↓
低风险：返回 DRAFTED 草稿 + 引用 + 人工确认按钮
高风险：返回转人工建议 + 风险原因
  ↓
客服确认或取消
  ↓
更新工单状态并写入回复审计日志
```

---

## 推荐模块结构

```text
modules/support-copilot/
  src/main/java/dev/qcoding/businesscopilot/supportcopilot/
    ticket/
    classification/
    knowledge/
    draft/
    guardrail/
    audit/
    web/
```

建议职责：

| 包 | 职责 |
|---|---|
| `ticket` | 工单输入、示例工单、工单状态 |
| `classification` | 工单分类、情绪识别、紧急程度判断 |
| `knowledge` | 知识库依据检索适配 |
| `draft` | 回复草稿生成、结构化模型输出 |
| `guardrail` | 高风险回复识别、引用校验、禁止自动承诺 |
| `audit` | 工单分析和回复确认审计 |
| `web` | REST API 和工作台入口 |

---

## 与 Knowledge Copilot 的关系

Support Copilot 应优先复用 Knowledge Copilot 已经实现的知识检索能力，但需要避免业务模块之间形成混乱依赖。

建议实现方式：

- Support Copilot 定义窄接口 `SupportKnowledgeRetriever`。
- 如果 Knowledge Copilot 已提供稳定的检索 facade，可以在 app 层装配适配器。
- 如果暂时没有 facade，Support Copilot 可以先实现基于 Knowledge Copilot REST/API 或只读 service 的适配。
- Knowledge Copilot 不反向依赖 Support Copilot。
- 当检索能力被多个模块稳定复用后，再考虑沉淀到平台层。

回复草稿必须带有知识引用。没有足够依据时，不生成确定回复，只给出转人工或补充信息建议。

---

## 状态流转

Support Copilot 的 MVP 不自动发送任何客服消息，状态只用于表达草稿和人工确认进度。

```text
工单输入
  ↓
DRAFTED        已生成可确认草稿，等待客服确认
NEEDS_HUMAN    无知识依据、高风险或模型判断需人工处理
CONFIRMED      客服已确认草稿；MVP 只记录确认，不对外发送
CANCELED       客服取消草稿
```

重要约束：

- 生成低风险草稿后只能进入 `DRAFTED`，不能直接进入 `CONFIRMED`。
- `CONFIRMED` 只能由 `/reply-drafts/{draftId}/confirm` 使用服务端 token 触发。
- 无知识依据时不生成确定回复，直接进入 `NEEDS_HUMAN`。
- `CONFIRMED` 和 `CANCELED` 都必须写审计，并保留 `ticketId` 关联。

---

## 数据模型草案

### support_tickets

| 字段 | 说明 |
|---|---|
| id | 工单 ID |
| external_id | 外部工单编号，可为空 |
| customer_message | 脱敏后的客户问题 |
| channel | 渠道，例如 web、email、chat、sample |
| category | 分类 |
| sentiment | 情绪 |
| urgency | 紧急程度 |
| status | DRAFTED / NEEDS_HUMAN / CONFIRMED / CANCELED |
| created_at | 创建时间 |
| updated_at | 更新时间 |

### support_reply_drafts

| 字段 | 说明 |
|---|---|
| id | 草稿 ID |
| ticket_id | 工单 ID |
| draft_text | 脱敏后的回复草稿 |
| cited_chunk_ids | 引用知识片段 |
| risk_level | LOW / MEDIUM / HIGH |
| risk_reasons | 风险原因 |
| confirmation_token | 确认 token |
| expires_at | 过期时间 |
| created_at | 创建时间 |

### support_audit_logs

| 字段 | 说明 |
|---|---|
| id | 审计 ID |
| request_id | 请求 ID |
| ticket_id | 工单 ID |
| event_type | CLASSIFIED / DRAFTED / NEEDS_HUMAN / CONFIRMED / CANCELED / FAILED |
| category | 分类 |
| urgency | 紧急程度 |
| risk_level | 风险等级 |
| cited_chunk_ids | 引用片段 |
| model_name | 模型名 |
| latency_ms | 耗时 |
| error_message | 错误信息 |
| created_at | 创建时间 |

审计日志不记录客户原始敏感信息，不记录未脱敏的回复内容。

---

## API 草案

Base path: `/api/support-copilot`

### POST /tickets/analyze

分析工单并生成回复草稿。

Request:

```json
{
  "customerMessage": "我昨天付款后功能还没有开通，订单号 BC20260708001，请马上处理，否则我要投诉。",
  "channel": "sample"
}
```

Response:

```json
{
  "success": true,
  "data": {
    "requestId": "req-001",
    "ticketId": "ticket-001",
    "category": "ACCOUNT_ACTIVATION",
    "sentiment": "FRUSTRATED",
    "urgency": "HIGH",
    "draft": {
      "draftId": "draft-001",
      "replyText": "您好，我们理解您已经付款但功能尚未开通的情况会影响使用。请您确认订单号是否为 BC2026****0001，我们会根据开通流程协助核查。",
      "riskLevel": "MEDIUM",
      "riskReasons": ["涉及订单状态，需要人工核查后再承诺处理时效"],
      "citations": [
        {
          "sourceTitle": "产品开通 FAQ",
          "sectionTitle": "付款后开通时间",
          "snippet": "付款成功后通常会在系统确认后开通；如超过约定时间，需要人工核查订单状态。"
        }
      ],
      "confirmationToken": "token-001",
      "expiresAt": "2026-07-08T10:00:00Z"
    },
    "needsHuman": true
  }
}
```

### POST /reply-drafts/{draftId}/confirm

人工确认草稿。MVP 中会更新工单状态并记录审计，但不对外发送消息。

Request:

```json
{ "confirmationToken": "token-001" }
```

Response:

```json
{
  "success": true,
  "data": {
    "draftId": "draft-001",
    "status": "CONFIRMED"
  }
}
```

### POST /reply-drafts/{draftId}/cancel

取消草稿并记录审计。

### GET /audit-logs?page=0&size=20

返回客服辅助处理审计日志。

---

## Prompt 模板

建议集中放在：

```text
platform/ai-core/src/main/resources/prompts/support-copilot/ticket-classification.st
platform/ai-core/src/main/resources/prompts/support-copilot/reply-draft.st
```

分类 prompt 输出示例：

```json
{
  "category": "REFUND",
  "sentiment": "FRUSTRATED",
  "urgency": "HIGH",
  "summary": "客户要求退款并表达强烈不满",
  "needsHuman": true,
  "reasons": ["退款诉求", "情绪激烈"]
}
```

回复草稿 prompt 输出示例：

```json
{
  "replyText": "您好，我们理解您的情况。根据退款流程，申请退款需要提供订单号、退款原因和问题截图。",
  "riskLevel": "MEDIUM",
  "riskReasons": ["涉及退款，需要人工确认资格"],
  "citations": [
    { "chunkId": "chunk-001", "reason": "说明退款材料要求" }
  ],
  "needsHuman": true
}
```

Prompt 约束：

- 只能基于客户工单和给定知识片段生成回复。
- 不得承诺退款、赔偿、开通、关闭、合同变更或明确时效。
- 不得编造订单状态、账号状态、客户身份和处理结果。
- 高风险问题必须建议转人工。
- 回复语气应礼貌、克制、可直接由客服修改。
- `replyText` 不得包含 token、secret、password、id_card 等敏感内容。

---

## 安全边界

| 安全机制 | 说明 |
|---|---|
| 人工确认 | 所有回复草稿必须人工确认，MVP 不自动发送 |
| 高风险转人工 | 退款、赔偿、法律投诉、账号安全、生产故障等默认建议人工处理 |
| 引用依据 | 回复草稿必须携带知识来源；无依据时不生成确定回复 |
| 无依据降级 | 检索不到知识依据时直接进入 NEEDS_HUMAN，不调用模型生成确定草稿 |
| 禁止承诺 | 不允许直接承诺退款、赔偿、开通、关闭、合同变更和明确时效 |
| 敏感信息 | phone、email、订单号等脱敏；token、secret、password、id_card 阻断或全遮蔽 |
| 审计日志 | 记录分析、草稿、确认、取消和失败，不记录未脱敏原文 |
| 示例数据安全 | 示例工单和客户信息必须虚构 |

---

## 示例工单

工作台在 `static/js/support-copilot.js` 中提供产品使用、账号注册、退款流程和生产故障四组虚构示例。前两组用于演示知识命中后的低风险建议，后两组用于演示高风险人工复核。示例只填充前端表单，不作为后端业务数据或独立资源文件维护。

---

## 已知限制

- MVP 不对接真实客服渠道。
- MVP 不自动发送消息。
- MVP 不查询真实订单或客户状态。
- 回复质量依赖知识库内容完整度。
- 情绪和紧急程度识别只能作为辅助，不作为唯一处理依据。
- 高风险问题宁可转人工，不追求自动化闭环。

---

## 框架迁移边界

- `support_tickets`、`support_reply_drafts`、`support_audit_logs` 由显式 JDBC Repository 管理。
- 业务 Repository 接口、摘要 token、条件状态流转和 Guardrails 保持独立。
- Support 继续通过 `SupportKnowledgeRetriever` 复用 Knowledge，不复制 vector 查询。
- Spring AI 结构化输出只替代 JSON 解析，不替代高风险分类、引用校验和禁止承诺规则。
- Support 对 `ai-tool-audit` 的未使用依赖已删除；Support 保留自己的业务审计模型。
- 不为了未来客服平台引入工单工作流、消息队列或外部渠道抽象。
