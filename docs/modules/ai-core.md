# ai-core

## 职责

提供模型调用、Jackson 3 结构化输出、Embedding 调用和集中 Prompt 模板加载。它不知道任何业务状态。

## 流程

```mermaid
flowchart LR
    SERVICE["Business Service"] --> TEMPLATE["PromptTemplateService"]
    TEMPLATE --> CHAT["AiChatService"]
    CHAT --> MODEL["Spring AI ChatClient"]
    MODEL --> JSON["Typed Output"]
    JSON --> GUARD["Business Guardrail"]
```

## 边界

- 模型禁用时应用仍可启动，调用返回明确业务错误。
- Prompt 不散落在 service。
- ai-core 不持久化业务数据，不决定业务状态。

## v1.2 升级范围

- Chat/Embedding 调用返回 provider、model、latency、token usage、finish reason 和 provider request id。
- Prompt 加载结果包含 name、version、content hash 和渲染内容。
- 业务模块从调用结果读取准确元数据，不再自行猜测模型名或写 `unknown`。
- 审计不保存完整 Prompt 和完整模型输出。

## 验证

`./mvnw -pl platform/ai-core -am test`
