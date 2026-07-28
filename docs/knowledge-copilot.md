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
- 文档分类与 `ALL / HR_REVIEWER / ADMIN` 服务端访问范围
- 简单知识库工作台
- Docker Compose 一键启动 pgvector

暂不实现：

- 多租户
- 登录注册和复杂权限
- 任意粒度用户 ACL 和通用文档管理能力
- 尚未在部署方真实沙箱验证的外部连接器
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
按当前角色和服务端业务分类过滤，再进行低分拒答判断
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

当前支持：

- TXT
- Markdown
- PDF
- DOCX

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
  "category": "SUPPORT_POLICY"
}
```

Response:

```json
{
  "success": true,
  "data": {
    "answer": "客户申请退款时，需要提供订单号、退款原因、支付凭证和问题截图。",
    "status": "ANSWERED",
    "citations": [
      {
        "chunkId": 101,
        "excerpt": "退款申请需提供订单号、退款原因、支付凭证和问题截图。"
      }
    ],
    "warnings": [],
    "modelName": "configured-chat-model",
    "answerId": 9001
  }
}
```

无依据时：

```json
{
  "success": true,
  "data": {
    "answer": null,
    "status": "NO_EVIDENCE",
    "citations": [],
    "warnings": [],
    "answerId": 9002
  }
}
```

### POST /answers/{answerId}/feedback

当前操作者只能提交或更新自己问答记录的反馈。负反馈必须选择稳定原因：

```json
{
  "rating": "NOT_HELPFUL",
  "reason": "MISSING_EVIDENCE",
  "comment": "缺少退款材料的有效期要求"
}
```

`rating` 支持 `HELPFUL`、`NOT_HELPFUL`；负反馈原因支持 `MISSING_EVIDENCE`、`INCORRECT`、`OUTDATED`、`UNCLEAR`、`OTHER`。
`comment` 在入库前遮蔽手机号、邮箱、身份证号和凭据赋值。

### GET /quality-queue?page=0&size=20

返回尚未处置或在最近处置后再次更新的 `NO_EVIDENCE`、`REJECTED` 和负反馈问答，仅 Admin/Reviewer 可读。每项包含单调递增的 `issueVersion` 和用于展示的 `issueUpdatedAt`；并发判断以修订号为准。

### POST /quality-queue/{answerId}/review

仅 Admin/Reviewer 可记录人工处置，且必须填写说明：

```json
{
  "decision": "KNOWLEDGE_UPDATE_REQUIRED",
  "reviewNote": "需要补充最新的退款材料有效期",
  "expectedIssueVersion": 2,
  "expectedIssueUpdatedAt": "2026-07-28T12:00:00Z"
}
```

`decision` 支持 `RESOLVED`、`DISMISSED`、`KNOWLEDGE_UPDATE_REQUIRED`。问题已被处理或反馈已更新时返回状态冲突，客户端必须刷新后再决定。
`reviewNote` 使用与知识文档相同的自由文本脱敏规则后再持久化。

### GET /quality-metrics

返回反馈总数、正负反馈、待复核以及三类人工处置计数，不包含问题、备注或其他业务原文。

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

- 2.2 已提供 SharePoint、Confluence、Notion、本地挂载目录和 S3/MinIO 的增量同步代码，支持源端删除传播、过期/冲突提示和固定用户组到业务可见范围的失败关闭映射。
- 连接器不建设任意 IAM；生产声明仍以部署方真实来源沙箱、凭证、组映射和删除传播验收为准。
- PDF/DOCX 只做有界文本提取，不支持扫描件 OCR。
- 文档更新使用逻辑文档 ID 和版本，当前版本切换后旧版本不再参与检索。
- pgvector 检索按 embedding 模型和向量维度隔离。
- 没有 embedding key 或模型不可用时，文档可完成文本索引并使用全文/中文关键词检索。
- 检索质量依赖文档结构、分片策略和 embedding 模型。
- 反馈和质量队列只辅助人工复核，不会自动改变知识库或模型行为；“转知识维护”只是显式人工任务结论，不会自动改写资料。

---

## 框架迁移边界

- `knowledge_documents`、`knowledge_chunks`、`knowledge_qa_audit_logs` 和 embedding 检索均由显式 JDBC Repository 管理。
- `JdbcKnowledgeEmbeddingRepository` 保留 vector 参数和 `<=>` 距离查询的清晰控制。
- Repository 接口保持稳定，模块自动配置不依赖宿主 Mapper 扫描。
- 文档、分片和向量必须继续受业务事务控制，不能由 Mapper 自行编排。
- 回答生成迁移到 Spring AI 2.0 `ChatClient.entity(...)` 后，Citation Guardrail 仍然必须执行。
- 不引入通用 vector ORM、知识图谱或新的 persistence 平台模块。
