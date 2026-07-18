# Prompt 04: Data Copilot REST API

```text
请实现 Data Copilot REST API，把已完成的 schema、SQL 生成、确认、执行、解释、审计能力串成闭环。

包名：
- dev.qcoding.businesscopilot.datacopilot.web

API：
- GET /api/data-copilot/schema
- POST /api/data-copilot/sql-candidates
- POST /api/data-copilot/sql-candidates/{candidateId}/execute
- GET /api/data-copilot/audit-logs?page=0&size=20

请求响应：
- 使用 common-web 的 ApiResponse。
- 使用 Jakarta Validation。
- execute 请求体只允许 confirmationToken，不允许传 SQL。
- 错误响应不能暴露堆栈。

流程：
- /schema：返回白名单 schema 摘要。
- /sql-candidates：生成 SQL，guardrails 校验，通过时保存候选并返回 candidateId/token。
- /execute：根据 candidateId/token 取服务端候选，二次校验，执行 SQL，脱敏，生成解释，写审计，返回 table + explanation。
- /audit-logs：返回最近审计日志，分页即可，不做复杂筛选。

轻量测试：
- /sql-candidates 参数为空时返回校验错误。
- guardrails 失败时 executable=false 且没有 token。
- /execute 不接受客户端 SQL。
- execute 成功返回 table + explanation。

边界：
- 不做登录注册。
- 不做权限系统。
- 不做管理后台。
- 不暴露审计表给自然语言 schema。
```
