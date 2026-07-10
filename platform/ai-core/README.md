# ai-core

English | [简体中文](#简体中文)

## English

Spring AI integration boundary for model calls, prompt loading, embeddings, and structured output.

### Current API

- `AiChatService`: text and JSON-oriented chat calls.
- `AiEmbeddingService`: embedding generation and dimension reporting.
- `PromptTemplateService`: loads centralized `.st` prompt resources.
- `JsonOutputParser`: current tolerant JSON parser.

Prompt resources are grouped by business module under `src/main/resources/prompts/`.

### Framework Plan

- Keep Spring AI `2.0.0` and `ChatClient`.
- Replace the Jackson 2 parser path with `ChatClient.entity(...)` and schema validation.
- Standardize on Jackson 3 managed by Spring Boot 4.
- Separate system instructions from untrusted user/business data.
- Record model metadata through Spring AI responses and observations.

This module must not contain business-specific guardrails.

### Test

```bash
../../mvnw -f ../../pom.xml -pl platform/ai-core -am test
```

## 简体中文

`ai-core` 是 Spring AI 调用边界，负责模型调用、Prompt 加载、embedding 和结构化输出。

当前项目已经使用 Spring AI 2.0.0。后续重点不是升级大版本，而是改用 `ChatClient.entity(...)`、schema validation 和 Jackson 3，删除 Jackson 2/3 双栈。

业务规则和业务 Guardrails 不应放入本模块。
