# support-copilot

English | [简体中文](#简体中文)

Customer-support assistant that classifies a ticket, binds versioned knowledge evidence, and produces an editable reply draft for human confirmation.

```mermaid
flowchart LR
    Ticket --> Mask --> Classify --> VersionedKnowledgeEvidence --> EditableReplyDraft --> RiskGuardrail --> ConfirmOrCancel
```

Tickets and drafts use explicit persisted state transitions. Every edit is revalidated; feedback and the final decision outcome are recorded. It never sends messages or performs refunds/account changes. High-risk categories, forbidden commitments, and missing or stale evidence trigger human handoff.

API: `POST /api/support-copilot/tickets/analyze`, `POST /api/support-copilot/reply-drafts/{id}/edit|confirm|cancel`.

Test: `./mvnw -pl modules/support-copilot -am test`

## 简体中文

智能客服助手，完成工单分类、风险识别、版本化知识检索和可编辑回复草稿。工单与草稿使用显式状态机，编辑内容会重新校验，并记录反馈和最终处理结果。不会自动发送或执行退款/账号操作，高风险或依据失效时必须转人工。
