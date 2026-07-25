# support-copilot

English | [简体中文](#简体中文)

Customer-support assistant that classifies a ticket, binds versioned knowledge evidence, and produces an editable reply draft for human confirmation.

```mermaid
flowchart LR
    Ticket --> Mask --> Classify --> VersionedKnowledgeEvidence --> EditableReplyDraft --> RiskGuardrail --> ConfirmOrCancel
```

Tickets and drafts use explicit persisted state transitions. Every edit is revalidated; feedback and the final decision outcome are recorded. It never sends messages or performs refunds/account changes. High-risk categories, forbidden commitments, and missing or stale evidence trigger human handoff.

The business workbench also provides a review queue filtered by status, category, urgency, and risk. It shows the reply suggestion first, followed by evidence versions, handoff reasons, edit differences, and the human outcome.

API: `GET /api/support-copilot/tickets`, `POST /api/support-copilot/tickets/analyze`, `POST /api/support-copilot/reply-drafts/{id}/edit|confirm|cancel`.

Test: `./mvnw -pl modules/support-copilot -am test`

## 简体中文

客服工作台完成工单分类、风险识别、版本化知识检索和可编辑回复草稿，并提供按状态、分类、紧急度和风险筛选的人工复核队列。页面优先显示回复建议，再展示依据版本、转人工原因、人工修订差异和处理结果。不会自动发送或执行退款/账号操作，高风险或依据失效时必须转人工。
