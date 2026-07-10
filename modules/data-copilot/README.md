# data-copilot

English | [简体中文](#简体中文)

## English

Natural-language database query assistant with human-confirmed, read-only SQL execution.

### Flow

1. Build a whitelist-based schema context.
2. Use Spring AI to generate a structured SQL candidate.
3. Apply SQL guardrails.
4. Store the server-side candidate and display SQL to the user.
5. Confirm by token, validate again, and execute through JDBC.
6. Mask results, generate a degradable explanation, and audit the lifecycle.

### Safety Boundary

- Only one `SELECT` or `WITH ... SELECT` statement.
- Queryable tables and sensitive columns are controlled by configuration.
- Client-provided SQL is never executed.
- Timeout and row limits are enforced at execution.

### Persistence Decision

`JdbcReadOnlyQueryExecutor` and `JdbcSchemaMetadataRepository` must remain JDBC-based. Only the stable audit table is a MyBatis-Plus migration candidate.

### Test

```bash
../../mvnw -f ../../pom.xml -pl modules/data-copilot -am test
```

## 简体中文

Data Copilot 通过自然语言生成只读 SQL，展示给用户确认后执行，并返回脱敏表格和 AI 解释。

动态 SQL executor 和 schema metadata 必须继续使用 JDBC，不能为了统一技术栈强行迁移到 MyBatis-Plus；仅稳定的审计表适合迁移。
