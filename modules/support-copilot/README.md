# support-copilot

English | [简体中文](#简体中文)

## English

Customer-support assistant that classifies tickets, retrieves knowledge evidence, and generates human-confirmed reply drafts.

### Flow

1. Sanitize the ticket.
2. Classify category, sentiment, urgency, and risk.
3. Retrieve Knowledge Copilot evidence.
4. Generate an evidence-backed reply draft.
5. Apply commitment and citation guardrails.
6. Return `DRAFTED` or `NEEDS_HUMAN`.
7. Confirm or cancel by server-side token and record audit.

### Safety Boundary

- No automatic message delivery.
- No refund, compensation, order, account, or contract action.
- No deterministic reply without knowledge evidence.
- High-risk tickets are escalated to a human.

### Persistence Plan

Ticket, reply-draft, and support-audit CRUD are MyBatis-Plus migration candidates. Knowledge retrieval stays behind `SupportKnowledgeRetriever`.

### Test

```bash
../../mvnw -f ../../pom.xml -pl modules/support-copilot -am test
```

## 简体中文

Support Copilot 对工单进行分类、情绪和紧急程度识别，检索知识依据并生成需要人工确认的回复草稿。

它不自动发送消息，也不执行退款、赔偿、订单、账号和合同操作。工单、草稿、审计 CRUD 计划迁移到 MyBatis-Plus，知识检索继续通过窄接口复用 Knowledge Copilot。
