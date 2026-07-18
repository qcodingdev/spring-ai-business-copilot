# Claude Code Prompt：V3 Support Copilot

本文档用于在 Data Copilot 和 Knowledge Copilot 完成后，实现第三个业务模块：Support Copilot 智能客服助手。

执行建议：

1. 每次只执行一个编号 prompt。
2. 先阅读并遵守 `AGENTS.md`、`docs/project-plan.md`、`docs/module-plan.md`、`docs/knowledge-copilot.md`、`docs/support-copilot.md`。
3. 不破坏 Data Copilot 和 Knowledge Copilot 已有功能。
4. Support Copilot 只做客服辅助，不做完整客服系统。
5. 不自动发送回复，不执行真实订单、退款、赔偿、账号变更等业务动作。

## 0. V3 总约束 Prompt

```text
请在当前 Spring AI Business Copilot 仓库中实现第三个业务模块：Support Copilot 智能客服助手。

实现前必须阅读：
- AGENTS.md
- docs/project-plan.md
- docs/module-plan.md
- docs/data-copilot.md
- docs/knowledge-copilot.md
- docs/support-copilot.md

项目定位：
- Data Copilot 已实现结构化数据查询。
- Knowledge Copilot 已实现企业知识库问答。
- Support Copilot 基于工单文本和知识库依据生成客服回复草稿。
- 它不是完整客服平台，不自动发送消息，不执行真实业务操作。

技术栈：
- Java 21
- Maven 多模块
- Spring Boot 4.1.x
- Spring AI 2.0.x
- Spring Web MVC
- Spring JDBC
- Thymeleaf + 原生 JavaScript

新增模块：
- modules/support-copilot

建议包名前缀：
- dev.qcoding.businesscopilot.supportcopilot

依赖方向：
- app -> support-copilot
- support-copilot -> ai-core
- support-copilot -> ai-guardrails
- support-copilot -> ai-tool-audit
- support-copilot -> common-web
- support-copilot 可以通过窄接口只读复用 Knowledge Copilot 检索能力，但 Knowledge Copilot 不能反向依赖 Support Copilot。

绝对边界：
- 不接入真实客服系统。
- 不自动发送邮件、短信、IM 或客服消息。
- 不执行真实退款、赔偿、订单、账号、合同操作。
- 不实现客服排班、SLA 流转、多渠道会话聚合。
- 不实现多租户、登录注册、复杂权限。
- 不实现 Resume Copilot 或 Report Copilot。
- 不让模型在没有知识依据时输出确定客服答复。
- 不把 prompt 大段写在 service 代码中。
- 不提交真实客户数据、真实工单或真实 API Key。

完成后至少运行：
- ./mvnw -q -DskipTests compile
- ./mvnw -q -pl modules/support-copilot -am test
```

## 1. 模块骨架、数据表和示例工单 Prompt

```text
请新增 Support Copilot 的 Maven 模块、数据库基础结构和虚构示例工单。

目标：
- 新增 modules/support-copilot 模块。
- 将模块接入根 pom 和 app/business-copilot-app。
- 增加 Flyway 迁移。
- 增加虚构示例工单数据。
- 保持 Data Copilot 和 Knowledge Copilot 现有功能不受影响。

请实现：
- modules/support-copilot/pom.xml
- SupportCopilotAutoConfiguration
- META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
- SupportCopilotProperties
- app 模块依赖 support-copilot
- Flyway 迁移：
  - support_tickets
  - support_reply_drafts
  - support_audit_logs

表设计要求：
- support_tickets 记录 external_id、customer_message、channel、category、sentiment、urgency、status、created_at、updated_at。
- support_reply_drafts 记录 ticket_id、draft_text、cited_chunk_ids、risk_level、risk_reasons、confirmation_token、expires_at、created_at。
- support_audit_logs 记录 request_id、ticket_id、event_type、category、urgency、risk_level、cited_chunk_ids、model_name、latency_ms、error_message、created_at。
- 客户消息和草稿内容入库前必须脱敏。
- 审计表不记录未脱敏原文。

配置要求：
- application.yml 增加 support-copilot 配置段：
  - enabled
  - max-ticket-length
  - draft-ttl
  - high-risk-categories
  - auto-human-handoff-enabled
  - knowledge-top-k

示例工单：
- app/business-copilot-app/src/main/resources/sample-support/refund-tickets.json
- app/business-copilot-app/src/main/resources/sample-support/activation-tickets.json
- app/business-copilot-app/src/main/resources/sample-support/incident-tickets.json
- app/business-copilot-app/src/main/resources/sample-support/account-security-tickets.json

示例数据要求：
- 全部虚构。
- 不包含真实个人信息。
- 可覆盖退款、开通、故障、账号安全四类场景。

测试：
- 模块能编译。
- AutoConfiguration 能加载。
- Repository 可写入脱敏后的工单和审计记录。

边界：
- 不实现工单分析业务。
- 不调用模型。
- 不接真实客服系统。
```

## 2. 工单解析、分类和风险识别 Prompt

```text
请实现 Support Copilot 的工单解析、分类、情绪识别、紧急程度判断和初步风险识别。

包名建议：
- dev.qcoding.businesscopilot.supportcopilot.ticket
- dev.qcoding.businesscopilot.supportcopilot.classification

Prompt 文件：
- platform/ai-core/src/main/resources/prompts/support-copilot/ticket-classification.st

请实现：
- SupportTicket
- SupportTicketRepository
- JdbcSupportTicketRepository
- TicketAnalysisService
- TicketClassificationService
- TicketClassificationRequest
- TicketClassificationResponse
- TicketCategory
- TicketSentiment
- TicketUrgency
- SupportRiskLevel

业务规则：
- customerMessage 必须非空且长度不超过 max-ticket-length。
- 输入入库前必须通过 SensitiveTextMasker 或等价能力脱敏。
- 模型输出必须是结构化 JSON。
- 分类至少包含：REFUND、ACCOUNT_ACTIVATION、INCIDENT、ACCOUNT_SECURITY、BILLING、PRODUCT_USAGE、OTHER。
- 情绪至少包含：NEUTRAL、CONFUSED、FRUSTRATED、ANGRY。
- 紧急程度至少包含：LOW、MEDIUM、HIGH、CRITICAL。
- 退款、赔偿、法律投诉、账号安全、生产故障、客户强烈投诉默认标记 needsHuman=true。
- 模型失败时返回清晰错误并写审计。

测试：
- 正常分类成功。
- 空输入拒绝。
- 超长输入拒绝。
- 敏感信息脱敏。
- 高风险类型标记 needsHuman。
- 模型 JSON 格式错误时错误清晰。

边界：
- 不生成回复草稿。
- 不检索知识库。
- 不自动发送任何消息。
```

## 3. 知识库依据检索适配 Prompt

```text
请实现 Support Copilot 的知识库依据检索适配。

包名建议：
- dev.qcoding.businesscopilot.supportcopilot.knowledge

目标：
- 根据工单摘要、分类和用户问题检索相关知识依据。
- 优先复用 Knowledge Copilot 已有检索能力。
- 保持模块边界清晰，避免 Knowledge Copilot 反向依赖 Support Copilot。

请实现：
- SupportKnowledgeRetriever
- SupportKnowledgeQuery
- SupportKnowledgeEvidence
- KnowledgeCopilotSupportKnowledgeRetriever（如果可直接使用 Knowledge Copilot 检索 service）
- FallbackSupportKnowledgeRetriever（无 Knowledge 检索能力时返回空结果和清晰原因）

业务规则：
- 检索输入由 customerMessage、category、summary 组成。
- topK 使用 support-copilot.knowledge-top-k 配置。
- 只使用 enabled 知识文档。
- 返回 evidence 必须包含 sourceTitle、sectionTitle、snippet、chunkId（如果来自 Knowledge Copilot）。
- 无证据时后续回复生成必须降级为 needsHuman 或要求补充知识。
- 无证据时不能生成确定回复草稿。

依赖规则：
- 如果 support-copilot 直接依赖 knowledge-copilot，会形成明确的单向依赖：support-copilot -> knowledge-copilot。
- 不允许 knowledge-copilot 依赖 support-copilot。
- 如果直接依赖过重，请在 app 层装配 adapter，support-copilot 只依赖接口。
- 不为了一个模块提前创建复杂 platform retrieval 框架。

测试：
- 有知识结果时返回 evidence。
- 无知识结果时返回空 evidence 和 reason。
- 只读取 enabled 文档。
- topK 配置生效。

边界：
- 不实现新的知识库系统。
- 不复制 Knowledge Copilot 的向量检索逻辑。
- 不做外部文档连接器。
```

## 4. 回复草稿生成、Guardrails 和确认机制 Prompt

```text
请实现 Support Copilot 的回复草稿生成、回复 guardrails 和人工确认机制。

包名建议：
- dev.qcoding.businesscopilot.supportcopilot.draft
- dev.qcoding.businesscopilot.supportcopilot.guardrail

Prompt 文件：
- platform/ai-core/src/main/resources/prompts/support-copilot/reply-draft.st

请实现：
- ReplyDraftService
- ReplyDraftRequest
- ReplyDraftResponse
- SupportReplyDraft
- SupportReplyDraftRepository
- JdbcSupportReplyDraftRepository
- ReplyDraftGuardrailService
- ReplyDraftConfirmationService
- InMemoryReplyDraftTokenStore 或数据库 token 校验
- ReplyDraftConfirmationProperties

回复生成规则：
- 只能基于工单内容和知识 evidence 生成草稿。
- 没有足够知识 evidence 时，不生成确定回复，返回 needsHuman=true。
- 模型输出必须是结构化 JSON。
- 输出至少包含 replyText、riskLevel、riskReasons、citations、needsHuman。
- citations 必须指向本次 evidence。
- replyText 非空时必须至少有一个有效 citation。
- 高风险问题默认 needsHuman=true。
- 禁止承诺退款、赔偿、开通、关闭、合同变更、明确处理时效。
- 禁止编造订单状态、账号状态、客户身份和处理结果。
- replyText 输出前必须脱敏。

确认机制：
- 草稿生成后返回 draftId + confirmationToken + expiresAt。
- 确认接口只接收 token，不接收草稿正文。
- 草稿生成后 ticket 状态保持 DRAFTED 或 NEEDS_HUMAN，不能直接 CONFIRMED。
- MVP 中确认会更新 ticket 状态并记录 CONFIRMED 审计，但不对外发送。
- token 过期后不可确认。
- 取消草稿要更新 ticket 状态并写 CANCELED 审计。

测试：
- 有 evidence 时生成草稿。
- 无 evidence 时 needsHuman。
- citation 不存在时拒绝。
- 高风险承诺被拦截。
- token 过期不可确认。
- 确认接口不信任客户端草稿正文。

边界：
- 不自动发送消息。
- 不执行真实业务操作。
- 不做长期会话记忆。
```

## 5. REST API、工作台和审计 Prompt

```text
请实现 Support Copilot 的 REST API、简单工作台和审计闭环。

包名建议：
- dev.qcoding.businesscopilot.supportcopilot.web
- dev.qcoding.businesscopilot.supportcopilot.audit

API：
- POST /api/support-copilot/tickets/analyze
- POST /api/support-copilot/reply-drafts/{draftId}/confirm
- POST /api/support-copilot/reply-drafts/{draftId}/cancel
- GET /api/support-copilot/audit-logs?page=0&size=20

请实现：
- SupportCopilotController
- TicketAnalyzeRequest / Response
- ReplyDraftConfirmRequest / Response
- SupportAuditService
- SupportAuditLog
- SupportAuditRepository
- JdbcSupportAuditRepository
- API DTO
- Thymeleaf 页面或现有首页中的 Support Copilot tab
- static js/css 的必要扩展

审计规则：
- 分类成功写 CLASSIFIED。
- 草稿生成成功写 DRAFTED。
- 无依据或高风险转人工写 NEEDS_HUMAN。
- 人工确认写 CONFIRMED。
- 用户取消写 CANCELED。
- 模型、检索、校验或执行异常写 FAILED。
- 审计不记录未脱敏客户原文，不记录未脱敏草稿。

前端要求：
- 工具型工作台，不做营销页。
- 工单输入区。
- 示例工单快捷填充。
- 分类、情绪、紧急程度展示。
- 回复草稿展示。
- 引用依据列表。
- 风险提示和转人工建议。
- 确认/取消按钮。
- 最近审计记录。
- loading 和错误状态清晰。
- 移动端基本可用。

测试：
- analyze API 成功。
- 无依据时 needsHuman。
- confirm API 成功。
- cancel API 成功。
- 审计日志写入。
- 页面基本渲染。

边界：
- 不接真实客服系统。
- 不自动发送任何消息。
- 不实现完整工单后台。
```

## 6. 文档、README 和收尾 Prompt

```text
请为 Support Copilot 补齐文档、README 入口和收尾验证。

目标：
- README 中说明第三模块状态和业务边界。
- Support Copilot 文档可指导用户理解和体验。
- Claude Code 执行文档入口加入 V3 prompt。
- 保持中英文公开入口一致。

文档要求：
- 更新 README.md。
- 更新 README.zh-CN.md。
- 更新 docs/project-plan.md。
- 更新 docs/module-plan.md。
- 更新 docs/support-copilot.md。
- 更新 docs/claude-code/README.md。
- 链接 docs/support-copilot.md。
- 明确 Support Copilot 不自动发送、不执行真实订单/退款/账号操作。

验证：
- ./mvnw -q -DskipTests compile
- ./mvnw -q -DskipTests package
- ./mvnw -q -pl modules/support-copilot -am test
- 如果部分测试依赖模型或外部服务不可用，请说明原因，并确保核心单元测试可独立运行。
```

## V3 Review Checklist

- [ ] 新增 `modules/support-copilot`，且不破坏 Data Copilot 和 Knowledge Copilot。
- [ ] 不自动发送客服消息。
- [ ] 不执行真实退款、赔偿、订单、账号、合同操作。
- [ ] 工单输入入库前已脱敏。
- [ ] 工单分类 prompt 集中在 resources。
- [ ] 回复草稿 prompt 集中在 resources。
- [ ] 高风险问题默认 needsHuman。
- [ ] 回复草稿必须基于知识 evidence。
- [ ] 没有 evidence 时不生成确定回复。
- [ ] citations 必须指向本次 evidence。
- [ ] 禁止承诺退款、赔偿、开通、关闭、合同变更和明确处理时效。
- [ ] 草稿确认使用服务端 token。
- [ ] 确认接口不信任客户端草稿正文。
- [ ] token 过期不可确认。
- [ ] 审计记录分类、草稿、转人工、确认、取消和失败。
- [ ] 审计不记录未脱敏客户原文。
- [ ] 示例工单全部为虚构内容。
- [ ] README 中英文入口已更新。
- [ ] `./mvnw -q -DskipTests compile` 通过。
