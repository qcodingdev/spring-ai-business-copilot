# ai-guardrails

English | [简体中文](#简体中文)

## English

Deterministic safety checks shared by real Copilot workflows.

### Current Capabilities

- Single-statement and read-only SQL validation.
- Forbidden keyword and schema whitelist checks.
- Sensitive-column blocking and result masking.
- Query limit enforcement.
- Sensitive free-text masking for Knowledge and Support Copilot.

### Boundaries

- Guardrails are deterministic application controls, not prompts.
- Spring AI structured output and MyBatis-Plus do not replace these checks.
- Data Copilot validates SQL both before confirmation and immediately before execution.
- Business-specific rules remain inside their business module until genuine reuse exists.

### Test

```bash
../../mvnw -f ../../pom.xml -pl platform/ai-guardrails -am test
```

## 简体中文

该模块提供可确定执行的安全校验：SQL 单语句、只读、白名单、敏感字段、LIMIT，以及文本敏感信息脱敏。

模型结构化输出和 MyBatis-Plus 都不能替代 Guardrails。业务专属规则继续留在业务模块中，不提前抽象。
