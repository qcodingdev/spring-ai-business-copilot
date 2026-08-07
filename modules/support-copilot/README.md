# support-copilot

English | [简体中文](#简体中文)

Customer-support assistant that classifies a ticket, binds versioned knowledge evidence, and produces an editable reply draft for human confirmation.

```mermaid
flowchart LR
    Ticket --> Mask --> Classify --> VersionedKnowledgeEvidence --> EditableReplyDraft --> RiskGuardrail --> ConfirmOrCancel
```

Tickets and drafts use explicit persisted state transitions. Every edit is revalidated; feedback and the final decision outcome are recorded. It never sends messages or performs refunds/account changes. High-risk categories, forbidden commitments, and missing or stale evidence trigger human handoff.

Enterprise adapters cover Jira Service Management, Zendesk, ServiceNow, Feishu, and WeCom ticket reads; sanitized customer/order/service snapshots, similar historical tickets, SLA risk, adoption/edit/handoff metrics, and confirmation-token-bound internal-note writeback are persisted. The workbench checks writeback capability after draft confirmation and exposes the intent/confirmation flow only when the ticket has both an external ID and an enabled connection context. Locally created tickets remain reviewable but cannot enter external writeback. Adapters never send a customer message or perform refunds/account changes and require provider sandbox verification before production use.

The 2.3 bilingual workbench makes tickets, human review, connections, and
quality/SLA visible. Connection setup accepts only a `secretRef`, and confirmed
external internal-note writes remain separate from ordinary saves.

API: `GET /api/support-copilot/tickets`, `POST /api/support-copilot/tickets/analyze`, `POST /api/support-copilot/reply-drafts/{id}/edit|confirm|cancel`.

Test: `./mvnw -pl modules/support-copilot -am test`

## 简体中文

客服工作台完成工单分类、风险识别、版本化知识检索和可编辑回复草稿。企业接入覆盖 Jira Service Management、Zendesk、ServiceNow、飞书和企微的只读工单导入，保存脱敏客户/订单/服务快照，提供相似历史工单、SLA 风险、采纳/修改/转人工统计，并只允许凭一次性确认 token 回写内部备注。草稿确认后会先检查回写资格，只有同时具备外部工单 ID 与连接上下文的导入工单才显示回写意图/确认流程；工作台直接创建的工单只允许复核，不会伪装成可回写。不会自动发送客户消息或执行退款/账号操作；各厂商仍需真实沙箱验收后再用于生产。

2.3 双语工作台展示工单、人工复核、连接和质量/SLA；连接页面只接受 `secretRef`，
外部内部备注写入继续与普通保存分离并要求重新确认。
