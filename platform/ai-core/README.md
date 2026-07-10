# ai-core

English | [简体中文](#简体中文)

## English

Spring AI integration boundary for model calls, prompt loading, embeddings, and structured output.

### Current API

- `AiChatService`: text and schema-validated structured chat calls.
- `AiEmbeddingService`: embedding generation and dimension reporting.
- `PromptTemplateService`: loads centralized `.st` prompt resources.

Prompt resources are grouped by business module under `src/main/resources/prompts/`.

### Framework Plan

- Keep Spring AI `2.0.0` and `ChatClient`.
- `ChatClient.entity(...)` with schema validation is now the default structured-output path.
- Application-owned JSON mapping now uses Spring Boot 4's Jackson 3 path; the OpenAI SDK retains its own transitive Jackson 2 compatibility dependency.
- Separate system instructions from untrusted user/business data.
- Record model metadata through Spring AI responses and observations.

This module must not contain business-specific guardrails.

### Test

```bash
../../mvnw -f ../../pom.xml -pl platform/ai-core -am test
```

## 简体中文

`ai-core` 是 Spring AI 调用边界，负责模型调用、Prompt 加载、embedding 和结构化输出。

当前项目使用 Spring AI 2.0.0，并已使用 `ChatClient.entity(...)`、schema validation 和 Jackson 3。后续重点是按 provider 能力决定是否启用原生 structured output，并完善模型元数据审计。

业务规则和业务 Guardrails 不应放入本模块。
