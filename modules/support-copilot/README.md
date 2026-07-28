# support-copilot

English | [简体中文](#简体中文)

Customer-support assistant that classifies a ticket, binds versioned knowledge evidence, and produces an editable reply draft for human confirmation.

```mermaid
flowchart LR
    Ticket --> Mask --> Classify --> VersionedKnowledgeEvidence --> EditableReplyDraft --> RiskGuardrail --> ConfirmOrCancel
```

Tickets and drafts use explicit persisted state transitions. Every edit is revalidated; feedback and the final decision outcome are recorded. It never sends messages or performs refunds/account changes. High-risk categories, forbidden commitments, and missing or stale evidence trigger human handoff.

Enterprise adapters cover Jira Service Management, Zendesk, ServiceNow, Feishu, and WeCom ticket reads; sanitized customer/order/service snapshots, similar historical tickets, SLA risk, adoption/edit/handoff metrics, and confirmation-token-bound internal-note writeback are persisted. Adapters never send a customer message or perform refunds/account changes and require provider sandbox verification before production use.

API: `GET /api/support-copilot/tickets`, `POST /api/support-copilot/tickets/analyze`, `POST /api/support-copilot/reply-drafts/{id}/edit|confirm|cancel`.

Test: `./mvnw -pl modules/support-copilot -am test`

## 简体中文

客服工作台完成工单分类、风险识别、版本化知识检索和可编辑回复草稿。企业接入覆盖 Jira Service Management、Zendesk、ServiceNow、飞书和企微的只读工单导入，保存脱敏客户/订单/服务快照，提供相似历史工单、SLA 风险、采纳/修改/转人工统计，并只允许凭一次性确认 token 回写内部备注。不会自动发送客户消息或执行退款/账号操作；各厂商仍需真实沙箱验收后再用于生产。
