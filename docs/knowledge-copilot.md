# Knowledge Copilot 模块文档

> **V2 已实现（2026-07）。** 当前实现覆盖 Markdown/TXT 文档、分片、embedding、pgvector 检索、有引用回答、无依据拒答和问答审计。

## 业务价值

Knowledge Copilot 是企业知识库助手，帮助团队从内部文档中快速获得有来源引用、可追溯、可审计的答案。

典型场景：

- 新员工查询制度、流程和产品资料
- 客服或售后根据 FAQ、操作手册生成答复依据
- 实施交付团队查询交付规范、验收材料和故障处理流程
- 产品和研发团队查询接口说明、发布记录和内部规范
- 管理者降低重复问答和新人培训成本

核心价值：**把散落文档变成可问答、可引用、可审计的内部知识服务。**

---

## 为什么作为第二模块

Data Copilot 解决结构化业务数据查询问题，Knowledge Copilot 解决非结构化知识查询问题。两者组合后，项目覆盖了企业内部 AI 应用中最常见的两类数据：

- 数据库中的结构化数据
- 文档中的非结构化知识

相比其他候选模块：

- 比 Resume Copilot 覆盖面更广，合规争议更少。
- 比 Support Copilot 更容易独立 demo，不强依赖客服系统和工单系统。
- 比 Report Copilot 更容易做出可信闭环，因为答案可以强制绑定文档引用。

---

## MVP 范围

第一版 Knowledge Copilot 只做“文档知识问答闭环”。

必须实现：

- Spring Boot 后端
- Spring AI ChatClient 调用
- Spring AI EmbeddingModel 调用
- PostgreSQL + pgvector 存储向量
- 示例知识文档
- 文档上传和启用/停用
- 文档解析
- 文档分片
- 文档 embedding
- 用户问题向量化
- topK 片段检索
- 基于检索片段生成答案
- 答案来源引用
- 无依据拒答
- 敏感信息脱敏
- 知识问答审计日志
- 简单知识库工作台
- Docker Compose 一键启动 pgvector

暂不实现：

- 多租户
- 登录注册和复杂权限
- 企业级文档权限继承
- Confluence、飞书、Notion、Google Drive 等外部连接器
- 在线协同编辑
- 大规模全文搜索后台
- 知识图谱
- 对话长期记忆
- 流式输出
- 自动定时同步

---

## 核心流程

```
管理员上传文档
  ↓
解析文本和元数据
  ↓
按标题、段落和长度分片
  ↓
敏感信息检测和脱敏
  ↓
调用 EmbeddingModel 生成向量
  ↓
写入 document / chunk / embedding 表

用户提问
  ↓
问题向量化
  ↓
向量检索 topK chunks
  ↓
检索结果安全过滤和低分拒答判断
  ↓
Prompt + chunks → LLM 生成答案
  ↓
引用完整性 guardrails
  ↓
返回 answer + citations
  ↓
写入知识问答审计日志
```

---

## 推荐模块结构

```text
modules/knowledge-copilot/
  src/main/java/dev/qcoding/businesscopilot/knowledgecopilot/
    document/
    chunking/
    embedding/
    retrieval/
    answer/
    citation/
    web/
```

建议职责：

| 包 | 职责 |
|---|---|
| `document` | 文档元数据、上传、解析、启用/停用 |
| `chunking` | 文档分片策略、chunk 元数据 |
| `embedding` | embedding 调用、向量存储 |
| `retrieval` | 问题向量化、topK 检索、召回阈值 |
| `answer` | answer prompt、答案生成、拒答策略 |
| `citation` | 引用结构、引用完整性校验 |
| `web` | REST API 和工作台入口 |

平台能力沉淀建议：

- `platform/ai-core` 增加 embedding service 和 prompt 模板加载复用能力。
- `platform/ai-guardrails` 增加文本敏感信息检测、答案引用校验、无依据拒答策略。
- `platform/ai-tool-audit` 扩展通用工具审计或新增知识问答审计模型。

注意：平台能力必须由 Knowledge Copilot 真实使用后再沉淀，不提前做大而全抽象。

---

## 数据模型草案

### knowledge_documents

| 字段 | 说明 |
|---|---|
| id | 文档 ID |
| title | 文档标题 |
| source_type | 来源类型，例如 upload、sample |
| source_name | 原始文件名或来源名 |
| category | 业务分类 |
| content_hash | 内容哈希，用于去重 |
| enabled | 是否启用 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

### knowledge_chunks

| 字段 | 说明 |
|---|---|
| id | chunk ID |
| document_id | 文档 ID |
| section_title | 所属章节 |
| chunk_index | 分片序号 |
| content | 脱敏后的 chunk 文本 |
| content_preview | 审计和列表展示用摘要 |
| token_count | 估算 token 数 |
| created_at | 创建时间 |

### knowledge_chunk_embeddings

| 字段 | 说明 |
|---|---|
| chunk_id | chunk ID |
| embedding_model | embedding 模型名 |
| embedding | pgvector 向量 |
| created_at | 创建时间 |

### knowledge_qa_audit_logs

| 字段 | 说明 |
|---|---|
| id | 审计 ID |
| request_id | 请求 ID |
| question | 用户问题 |
| retrieved_chunk_ids | 召回 chunk ID 列表 |
| cited_chunk_ids | 实际引用 chunk ID 列表 |
| answer_status | ANSWERED / NO_EVIDENCE / FAILED |
| refusal_reason | 拒答原因 |
| model_name | 回答模型 |
| embedding_model | embedding 模型 |
| latency_ms | 耗时 |
| created_at | 创建时间 |

审计日志不记录完整原始文档内容，不记录完整敏感字段值。

---

## API 草案

Base path: `/api/knowledge-copilot`

### POST /documents

上传知识文档。

MVP 支持：

- `.md`
- `.txt`

Response:

```json
{
  "success": true,
  "data": {
    "documentId": "doc-001",
    "title": "售后退款流程",
    "chunkCount": 12,
    "enabled": true
  }
}
```

### GET /documents

返回文档列表。

### PATCH /documents/{documentId}/enabled

启用或停用文档。

Request:

```json
{ "enabled": false }
```

### POST /questions

基于知识库问答。

Request:

```json
{
  "question": "客户申请退款时需要哪些材料？",
  "topK": 5
}
```

Response:

```json
{
  "success": true,
  "data": {
    "requestId": "req-001",
    "answer": "客户申请退款时，需要提供订单号、退款原因、支付凭证和问题截图。",
    "status": "ANSWERED",
    "citations": [
      {
        "documentId": "doc-001",
        "chunkId": "chunk-001",
        "title": "售后退款流程",
        "sectionTitle": "退款材料",
        "snippet": "退款申请需提供订单号、退款原因、支付凭证和问题截图。"
      }
    ],
    "warnings": []
  }
}
```

无依据时：

```json
{
  "success": true,
  "data": {
    "requestId": "req-002",
    "answer": "当前知识库中没有足够依据回答这个问题。",
    "status": "NO_EVIDENCE",
    "citations": [],
    "warnings": ["未检索到相似度足够的知识片段"]
  }
}
```

### GET /audit-logs?page=0&size=20

返回知识问答审计日志。

---

## Prompt 模板

建议集中放在：

```text
platform/ai-core/src/main/resources/prompts/knowledge-copilot/answer-generation.st
```

Prompt 约束：

- 只能基于给定 knowledge chunks 回答。
- 不得使用模型常识补充企业内部事实。
- 答案中的每个关键结论都必须能对应 citation。
- 没有足够依据时输出 `NO_EVIDENCE`。
- 不输出未在片段中出现的流程、金额、时间、承诺、联系方式。
- 不泄露 token、secret、password、id_card 等敏感内容。

结构化输出示例：

```json
{
  "status": "ANSWERED",
  "answer": "客户申请退款时，需要提供订单号、退款原因、支付凭证和问题截图。",
  "citations": [
    { "chunkId": "chunk-001", "reason": "说明退款材料要求" }
  ],
  "warnings": []
}
```

---

## 安全边界

| 安全机制 | 说明 |
|---|---|
| 基于证据回答 | 只允许基于召回 chunk 生成答案 |
| 必须有引用 | `ANSWERED` 状态必须至少包含一个有效 citation |
| 无依据拒答 | 召回不足、相似度过低、引用不完整时返回 `NO_EVIDENCE` |
| 敏感信息处理 | phone、email 默认脱敏；token、secret、password、id_card 阻断或全遮蔽 |
| 文档启用状态 | 只检索 enabled=true 的文档 |
| 审计日志 | 记录问题、召回 chunk、引用 chunk、状态、模型和耗时 |
| 示例数据安全 | 示例文档必须是虚构内容，不包含真实企业资料 |

---

## 示例知识文档

建议放在：

```text
app/business-copilot-app/src/main/resources/sample-knowledge/
```

示例文档：

- `product-faq.md`：虚构 SaaS 产品 FAQ
- `refund-policy.md`：虚构售后退款流程
- `incident-response.md`：虚构故障分级和响应流程
- `employee-handbook.md`：虚构员工差旅和报销规则

---

## 已知限制

- MVP 只支持 Markdown 和 TXT。
- MVP 不做用户级文档权限。
- MVP 不接外部文档系统。
- 文档更新后可以先采用删除旧 chunk、重建索引的方式。
- pgvector 维度需要与 embedding 模型配置一致。
- 没有 embedding key 或模型不可用时，文档上传可以保存元数据，但不可完成索引。
- 检索质量依赖文档结构、分片策略和 embedding 模型。
- 答案仍需人工判断，系统只提供带引用的辅助信息。

---

## 框架迁移边界

- `knowledge_documents`、`knowledge_chunks`、`knowledge_qa_audit_logs` 和 embedding 检索均由显式 JDBC Repository 管理。
- `JdbcKnowledgeEmbeddingRepository` 保留 vector 参数和 `<=>` 距离查询的清晰控制。
- Repository 接口保持稳定，模块自动配置不依赖宿主 Mapper 扫描。
- 文档、分片和向量必须继续受业务事务控制，不能由 Mapper 自行编排。
- 回答生成迁移到 Spring AI 2.0 `ChatClient.entity(...)` 后，Citation Guardrail 仍然必须执行。
- 不引入通用 vector ORM、知识图谱或新的 persistence 平台模块。
