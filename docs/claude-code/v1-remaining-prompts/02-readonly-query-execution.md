# Prompt 02: 只读查询执行

```text
请在 modules/data-copilot 中实现只读 SQL 执行和结果表格。

目标：
- 执行已确认的 SQL 候选。
- 返回前端可直接渲染的表格结构。
- 查询结果返回前必须脱敏。

包名：
- dev.qcoding.businesscopilot.datacopilot.query

请实现：
- ReadOnlyQueryExecutor
- JdbcReadOnlyQueryExecutor
- QueryResultTable
- QueryColumn
- QueryRow
- QueryExecutionProperties
- QueryExecutionException

要求：
- 使用 Spring JDBC。
- 执行前再次调用 SqlGuardrailService 做防御式校验。
- 设置 query timeout。
- 设置 max rows。
- 返回 columns、rows、rowCount、truncated。
- 返回前调用 SensitiveDataMasker，phone/email 等字段按现有策略脱敏。
- SQL 异常转换成用户可理解的 BusinessException，不暴露堆栈。
- 执行成功、执行失败都要预留审计调用点；如果审计生命周期尚未完全串起来，可以先在服务方法中调用 AuditService。

轻量测试：
- 成功查询返回 columns 和 rows。
- max rows 超出时 truncated=true。
- phone/email 返回前已脱敏。
- 非只读 SQL 在二次 guardrails 被拒绝。

边界：
- 不支持写操作。
- 不支持导出大文件。
- 不做后台长任务。
- 不支持用户自定义数据源。
```
