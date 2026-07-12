# support-copilot

English | [简体中文](#简体中文)

Customer-support assistant that classifies a ticket, retrieves knowledge evidence, and drafts a reply for human confirmation.

```mermaid
flowchart LR
    Ticket --> Mask --> Classify --> KnowledgeEvidence --> ReplyDraft --> RiskGuardrail --> ConfirmOrCancel
```

It never sends messages or performs refunds/account changes. High-risk categories, forbidden commitments, and missing evidence trigger human handoff.

API: `POST /api/support-copilot/tickets/analyze`, `POST /api/support-copilot/reply-drafts/{id}/confirm|cancel`.

Test: `./mvnw -pl modules/support-copilot -am test`

## 简体中文

智能客服助手，完成工单分类、风险识别、知识检索和回复草稿。不会自动发送或执行退款/账号操作，高风险内容必须转人工。
