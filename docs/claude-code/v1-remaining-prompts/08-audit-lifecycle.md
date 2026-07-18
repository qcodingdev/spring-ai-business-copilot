# Prompt 08: 审计生命周期补齐

```text
请补齐 Data Copilot 查询生命周期中的审计记录。

目标：
- 生成失败、校验失败、用户取消/未执行、执行成功、执行失败都能在审计中体现。

请检查并调整：
- SqlGenerationService
- SqlConfirmationService
- ReadOnlyQueryExecutor 或上层 orchestration service
- Data Copilot execute API
- AuditService / QueryAuditRepository 如有字段不匹配则做最小调整

要求：
- 审计记录包含 requestId、userQuestion、generatedSql、finalSql、validationStatus、validationErrors、confirmed、executionStatus、rowCount、errorMessage、modelName、latencyMs、createdAt。
- 不记录完整查询结果。
- 不记录敏感原始值。
- 用户生成 SQL 后未执行，第一版可以不做后台定时补偿；如果用户主动取消，则记录 cancelled。
- 审计查询 API 只返回必要字段，不返回内部异常堆栈。

轻量测试：
- 校验失败写审计。
- 执行成功写审计并包含 rowCount。
- 执行失败写审计并包含错误摘要。

边界：
- 不做复杂审计后台。
- 不做用户身份审计。
- 不接外部日志平台。
```
