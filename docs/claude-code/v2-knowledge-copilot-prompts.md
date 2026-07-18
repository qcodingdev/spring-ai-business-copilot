# Claude Code Prompt：V2 Knowledge Copilot

本文档用于在 Data Copilot 完成后，实现第二个业务模块：Knowledge Copilot 企业知识库助手。

执行建议：

1. 每次只执行一个编号 prompt。
2. 先阅读并遵守 `AGENTS.md`、`docs/project-plan.md`、`docs/module-plan.md`、`docs/knowledge-copilot.md`。
3. 不破坏 Data Copilot 已有功能。
4. 平台能力必须由 Knowledge Copilot 真实使用后再沉淀。
5. 不实现多租户、登录注册、复杂权限和外部文档连接器。

## 0. V2 总约束 Prompt

```text
请在当前 Spring AI Business Copilot 仓库中实现第二个业务模块：Knowledge Copilot 企业知识库助手。

实现前必须阅读：
- AGENTS.md
- docs/project-plan.md
- docs/module-plan.md
- docs/knowledge-copilot.md
- docs/data-copilot.md

项目定位：
- 第一模块 Data Copilot 已存在，不能破坏。
- 第二模块 Knowledge Copilot 面向企业内部文档问答。
- 它不是泛泛聊天机器人，必须只基于已上传、已索引、已检索到的知识片段回答。

技术栈：
- Java 21
- Maven 多模块
- Spring Boot 4.1.x
- Spring AI 2.0.x
- Spring Web MVC
- Spring JDBC
- PostgreSQL + pgvector
- Thymeleaf + 原生 JavaScript

新增模块：
- modules/knowledge-copilot

建议包名前缀：
- dev.qcoding.businesscopilot.knowledgecopilot

必须保留依赖方向：
- app -> data-copilot
- app -> knowledge-copilot
- knowledge-copilot -> ai-core
- knowledge-copilot -> ai-guardrails
- knowledge-copilot -> ai-tool-audit
- knowledge-copilot -> common-web

绝对边界：
- 不实现多租户。
- 不实现登录注册。
- 不实现复杂权限系统。
- 不接 Confluence、飞书、Notion、Google Drive。
- 不实现客服、简历、周报模块。
- 不让模型在没有引用证据时输出确定答案。
- 不把 prompt 大段写在 service 代码中。
- 不提交真实 API Key 或真实企业文档。

完成后至少运行：
- ./mvnw -q -DskipTests compile
- ./mvnw -q -pl modules/knowledge-copilot -am test
```

## 1. 模块骨架和 pgvector 数据库 Prompt

```text
请新增 Knowledge Copilot 的 Maven 模块和数据库基础结构。

目标：
- 新增 modules/knowledge-copilot 模块。
- 将模块接入根 pom 和 app/business-copilot-app。
- 增加 pgvector 相关 Flyway 迁移。
- 保持 Data Copilot 现有功能不受影响。

请实现：
- modules/knowledge-copilot/pom.xml
- KnowledgeCopilotAutoConfiguration
- META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
- app 模块依赖 knowledge-copilot
- Flyway 迁移：
  - 启用 vector 扩展
  - knowledge_documents
  - knowledge_chunks
  - knowledge_chunk_embeddings
  - knowledge_qa_audit_logs

表设计要求：
- knowledge_documents 记录文档标题、来源、分类、hash、enabled、created_at、updated_at。
- knowledge_chunks 记录 document_id、section_title、chunk_index、content、content_preview、token_count、created_at。
- knowledge_chunk_embeddings 记录 chunk_id、embedding_model、embedding vector、created_at。
- knowledge_qa_audit_logs 记录 request_id、question、retrieved_chunk_ids、cited_chunk_ids、answer_status、refusal_reason、model_name、embedding_model、latency_ms、created_at。

pgvector 要求：
- 使用 PostgreSQL pgvector 扩展。
- embedding 维度先通过配置项管理，例如 business-copilot.knowledge.embedding-dimension。
- 如果维度难以在 DDL 中动态配置，先选择一个明确默认值，并在 application.yml 和文档中说明必须与 embedding 模型一致。

配置要求：
- application.yml 增加 knowledge-copilot 配置段：
  - enabled
  - max-document-size
  - chunk-size
  - chunk-overlap
  - top-k
  - min-similarity
  - embedding-model-name

测试：
- 模块能编译。
- AutoConfiguration 能被加载。
- 数据库迁移 SQL 语法清晰，不影响已有 Data Copilot 迁移。

边界：
- 不实现文档上传业务。
- 不实现 embedding 调用。
- 不实现问答 API。
```

## 2. 文档上传、解析和分片 Prompt

```text
请实现 Knowledge Copilot 的文档上传、解析和分片能力。

包名建议：
- dev.qcoding.businesscopilot.knowledgecopilot.document
- dev.qcoding.businesscopilot.knowledgecopilot.chunking

目标：
- 支持上传 Markdown 和 TXT 文档。
- 解析文档文本和基础元数据。
- 按标题、段落和最大长度进行分片。
- 保存 document 和 chunk。

请实现：
- KnowledgeDocument
- KnowledgeChunk
- KnowledgeDocumentRepository
- JdbcKnowledgeDocumentRepository
- DocumentUploadService
- DocumentParser
- MarkdownDocumentParser
- TextDocumentParser
- ChunkingService
- ChunkingProperties
- DocumentUploadRequest / Response

业务规则：
- 只允许 .md、.markdown、.txt。
- 拒绝空文件。
- 拒绝超过 max-document-size 的文件。
- 使用 content_hash 防止重复上传完全相同内容。
- Markdown 尽量保留标题层级作为 sectionTitle。
- TXT 可按空行和长度分片。
- chunk 内容进入数据库前必须经过敏感信息处理。
- content_preview 只保存短摘要，避免审计和列表暴露完整内容。

敏感信息规则：
- phone、email 默认脱敏。
- password、token、secret、id_card 默认全遮蔽或阻断。
- 示例实现可先复用 ai-guardrails 中已有 SensitiveDataMasker，如果不适合，先沉淀文本脱敏能力到 ai-guardrails，并让 Data Copilot 不受影响。

测试：
- Markdown 标题分片。
- TXT 按段落分片。
- 空文件拒绝。
- 超大文件拒绝。
- 重复文件处理。
- 敏感信息脱敏。

边界：
- 不支持 PDF/DOCX。
- 不做外部文档连接器。
- 不做权限系统。
- 不调用 embedding 模型。
```

## 3. Embedding 和向量索引 Prompt

```text
请实现 Knowledge Copilot 的 embedding 生成和向量索引能力。

包名建议：
- dev.qcoding.businesscopilot.knowledgecopilot.embedding

目标：
- 对 knowledge_chunks 生成 embedding。
- 将向量保存到 PostgreSQL pgvector。
- 支持文档上传后同步索引。
- embedding 不可用时给出清晰错误。

平台能力：
- 如果 platform/ai-core 还没有 embedding 封装，请新增 AiEmbeddingService。
- AiEmbeddingService 应封装 Spring AI EmbeddingModel。
- 当 embedding model 未配置或调用失败时，抛出业务可理解异常，不要空指针。

请实现：
- AiEmbeddingService（如需要）
- KnowledgeEmbeddingService
- KnowledgeEmbeddingRepository
- JdbcKnowledgeEmbeddingRepository
- EmbeddingProperties
- EmbeddingIndexResult

业务规则：
- 对每个 chunk.content 生成 embedding。
- 保存 chunk_id、embedding_model、embedding、created_at。
- 同一 chunk 重建索引时先删除旧 embedding 再写入新 embedding。
- embedding_model 必须记录，方便后续排查模型切换问题。
- 向量维度和数据库字段不匹配时返回清晰错误。

测试：
- embedding service 调用成功时保存向量。
- embedding model 未启用时错误清晰。
- 重复索引不会生成重复 embedding。
- 维度不匹配错误可理解。

边界：
- 不做异步队列。
- 不做批量后台任务系统。
- 不做多 embedding 模型管理平台。
```

## 4. 检索、答案生成和引用 Guardrails Prompt

```text
请实现 Knowledge Copilot 的检索问答主流程。

包名建议：
- dev.qcoding.businesscopilot.knowledgecopilot.retrieval
- dev.qcoding.businesscopilot.knowledgecopilot.answer
- dev.qcoding.businesscopilot.knowledgecopilot.citation

目标：
- 用户输入问题。
- 问题向量化。
- 从 enabled 文档中检索 topK chunks。
- 召回不足时拒答。
- 基于 chunks 调用 LLM 生成结构化答案。
- 校验答案 citation 完整性。

Prompt 文件：
- platform/ai-core/src/main/resources/prompts/knowledge-copilot/answer-generation.st

请实现：
- KnowledgeQuestionService
- KnowledgeRetrievalService
- RetrievedKnowledgeChunk
- KnowledgeAnswerService
- KnowledgeAnswerRequest
- KnowledgeAnswerResponse
- KnowledgeCitation
- CitationGuardrailService
- KnowledgeAnswerStatus

检索规则：
- 只检索 enabled=true 的文档。
- topK 使用配置，默认 5。
- minSimilarity 使用配置，低于阈值不进入上下文。
- 没有足够片段时直接返回 NO_EVIDENCE，不调用 LLM 或允许不调用 LLM。

答案生成规则：
- Prompt 必须明确只能基于给定 chunks 回答。
- 模型输出必须是 JSON。
- JSON 至少包含 status、answer、citations、warnings。
- ANSWERED 状态必须至少有一个 citation。
- citations 中的 chunkId 必须来自本次 retrieved chunks。
- 如果模型引用了不存在的 chunkId，必须拒绝或降级为 NO_EVIDENCE。
- 如果模型输出确定答案但没有 citation，必须拒绝或降级为 NO_EVIDENCE。
- answer 中不得输出 token、secret、password、id_card 等敏感内容。

Prompt 约束：
- 不得使用模型常识补充企业内部流程、金额、承诺和联系方式。
- 不确定时输出 NO_EVIDENCE。
- 每个关键结论都要能对应 citation。
- 不要暴露 chunk 的内部调试信息。

测试：
- 有召回片段时生成 ANSWERED。
- 召回为空时 NO_EVIDENCE。
- 相似度过低时 NO_EVIDENCE。
- citation 指向不存在 chunk 时拒绝。
- ANSWERED 无 citation 时拒绝。
- 模型 JSON 格式错误时返回清晰错误。
- 敏感内容输出被脱敏或拒绝。

边界：
- 不做多轮对话记忆。
- 不做流式输出。
- 不做跨用户权限过滤。
```

## 5. REST API、工作台和审计 Prompt

```text
请实现 Knowledge Copilot 的 REST API、简单工作台和问答审计。

包名建议：
- dev.qcoding.businesscopilot.knowledgecopilot.web
- dev.qcoding.businesscopilot.knowledgecopilot.audit

目标：
- 提供文档管理 API。
- 提供知识问答 API。
- 提供审计日志 API。
- 在首页或独立页面提供简单 Knowledge Copilot 工作台入口。

API：
- POST /api/knowledge-copilot/documents
- GET /api/knowledge-copilot/documents
- PATCH /api/knowledge-copilot/documents/{documentId}/enabled
- POST /api/knowledge-copilot/questions
- GET /api/knowledge-copilot/audit-logs?page=0&size=20

请实现：
- KnowledgeCopilotController
- DocumentUploadController 或合并到 KnowledgeCopilotController
- KnowledgeQuestionController 或合并到 KnowledgeCopilotController
- KnowledgeAuditService
- KnowledgeQaAuditLog
- KnowledgeQaAuditRepository
- JdbcKnowledgeQaAuditRepository
- API DTO
- Thymeleaf 页面或在现有首页增加模块切换入口
- static js/css 的必要扩展

审计规则：
- 成功回答记录 ANSWERED。
- 无依据拒答记录 NO_EVIDENCE。
- 模型调用失败记录 FAILED。
- 检索失败记录 FAILED。
- 记录 retrieved_chunk_ids 和 cited_chunk_ids。
- 不记录完整原始文档内容。
- 不记录完整敏感字段值。

前端要求：
- 工具型工作台，不做营销页。
- 文档上传区域。
- 文档列表和启用/停用。
- 问题输入框。
- 答案区域。
- 引用来源列表。
- 无依据提示。
- 最近审计记录。
- loading 和错误状态清晰。
- 移动端基本可用。

测试：
- 上传文档 API。
- 文档列表 API。
- 启用/停用 API。
- 问答 API 成功和 NO_EVIDENCE。
- 审计日志写入。
- 页面基本渲染。

边界：
- 不实现登录和权限。
- 不实现企业级文档管理后台。
- 不做复杂 UI 框架迁移。
```

## 6. 示例知识库、Docker 和文档收尾 Prompt

```text
请为 Knowledge Copilot 补齐示例知识库、Docker Compose 配置和文档。

目标：
- 新增虚构示例知识文档。
- Docker Compose 使用支持 pgvector 的 PostgreSQL 镜像。
- README 中说明第二模块状态和启动方式。
- Knowledge Copilot 文档可指导用户运行和体验。

请新增示例文档：
- app/business-copilot-app/src/main/resources/sample-knowledge/product-faq.md
- app/business-copilot-app/src/main/resources/sample-knowledge/refund-policy.md
- app/business-copilot-app/src/main/resources/sample-knowledge/incident-response.md
- app/business-copilot-app/src/main/resources/sample-knowledge/employee-handbook.md

示例文档要求：
- 内容必须虚构。
- 不包含真实个人信息。
- 不包含真实公司内部资料。
- 有清晰标题和小节，方便分片和引用。

Docker 要求：
- examples/docker-compose.yml 使用 pgvector 可用镜像或明确启用 pgvector。
- 保留 Data Copilot 可运行。
- 环境变量说明 embedding 模型配置。
- 没有 API Key 时应用仍能启动，但 Knowledge 问答和索引要给出清晰错误。

README 要求：
- README.md 英文入口更新第二模块说明。
- README.zh-CN.md 中文入口更新第二模块说明。
- 链接 docs/knowledge-copilot.md。
- 明确 Knowledge Copilot 仍不包含登录、多租户、外部连接器。

文档要求：
- 更新 docs/project-plan.md。
- 更新 docs/module-plan.md。
- 更新 docs/knowledge-copilot.md。
- 更新 docs/claude-code/README.md，加入 V2 prompt 入口。

验证：
- ./mvnw -q -DskipTests compile
- ./mvnw -q -DskipTests package
- ./mvnw -q -pl modules/knowledge-copilot -am test
- 如果 mvn test 因本地 Docker 或模型配置不可用失败，请说明原因，并确保单元测试可独立运行。
```

## V2 Review Checklist

- [ ] 新增 `modules/knowledge-copilot`，且不破坏 Data Copilot。
- [ ] pgvector 迁移可运行。
- [ ] 文档上传支持 Markdown/TXT。
- [ ] 文档分片保留来源和章节。
- [ ] chunk 内容进入数据库前做敏感信息处理。
- [ ] embedding 调用有清晰配置和错误处理。
- [ ] 检索只使用 enabled 文档。
- [ ] topK 和 minSimilarity 可配置。
- [ ] 回答 prompt 集中在 resources。
- [ ] ANSWERED 必须有有效 citation。
- [ ] 无依据时返回 NO_EVIDENCE。
- [ ] 模型 JSON 解析失败有清晰错误。
- [ ] token、secret、password、id_card 不进入答案。
- [ ] 问答审计记录成功、拒答和失败。
- [ ] 审计不记录完整原始文档内容。
- [ ] 示例文档全部为虚构内容。
- [ ] Docker Compose 支持 pgvector。
- [ ] README 中英文入口已更新。
- [ ] `./mvnw -q -DskipTests compile` 通过。
