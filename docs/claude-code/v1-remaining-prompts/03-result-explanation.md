# Prompt 03: 查询结果解释

```text
请在 modules/data-copilot 中实现查询结果 AI 解释服务。

目标：
- 根据用户问题、已执行 SQL 和脱敏后的查询结果，生成简洁业务解释。
- 模型失败时不影响表格结果展示。

包名：
- dev.qcoding.businesscopilot.datacopilot.explanation

请实现：
- ResultExplanationService
- QueryResultSummarizer
- ResultExplanationRequest
- ResultExplanationResponse

要求：
- 使用 ai-core 中已有的 prompts/data-copilot/result-explanation.st。
- 只把脱敏后的结果摘要传给模型，不传完整大结果。
- 解释必须基于查询结果，不得编造数字。
- 空结果返回友好解释。
- 模型调用失败时返回降级解释，并记录 warn 日志。
- 中文问题优先中文解释，英文问题优先英文解释。

轻量测试：
- 结果摘要包含列名、行数和少量样例行。
- 空结果返回 no data / 未查询到匹配数据。
- 模型失败时返回降级解释。

边界：
- 不输出强经营建议。
- 不把敏感原始值发给模型。
- 不实现图表分析。
```
