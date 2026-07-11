# report-copilot

English | [简体中文](#简体中文)

## English

Source-grounded weekly report assistant. Its shared workbench collects a report period plus typed metric, task, and meeting evidence, previews normalized sources, renders the structured draft for review, and exposes confirmation or export only in the allowed state. `GET /api/report-copilot/sample-sources` exposes a sanitized evidence pack of fictional metrics, task progress, and meeting notes. `POST /api/report-copilot/source-previews` validates and normalizes client-provided metric, task, and meeting evidence. `POST /api/report-copilot/reports/generate` uses Spring AI structured output to create a persisted `DRAFTED` report, while `POST /api/report-copilot/reports/{draftId}/confirm` and `/cancel` require its server-generated token. `GET /api/report-copilot/reports/{draftId}/markdown` exports only confirmed drafts.

Every preview assigns server-generated source IDs and a SHA-256 hash based on sanitized content and metadata. This provides the evidence boundary that later structured generation, review, confirmation, and Markdown export will reuse.

Generated metric highlights must exactly match a cited metric source's name, value, and unit. Factual sections require valid source IDs; AI suggestions are explicitly separate and cannot claim source evidence. Model output is masked again before it reaches the response. Confirmation only records user approval; it never publishes a report or executes external actions. Markdown is rendered by the server from structured content and escapes model text.

### Current boundary

- No report publication.
- No user-provided or model-generated SQL.
- No external task, meeting, or BI integration.

### Test

```bash
../../mvnw -f ../../pom.xml -pl modules/report-copilot -am test
```

## 简体中文

Report Copilot 已接入共享工作台：用户可录入报告周期与结构化指标、任务、会议纪要来源，先预览归一化后的证据，再生成并审阅草稿。当前提供 `GET /api/report-copilot/sample-sources` 返回虚构来源；`POST /api/report-copilot/source-previews` 校验并归一化客户端来源；`POST /api/report-copilot/reports/generate` 通过 Spring AI 结构化输出生成并持久化 `DRAFTED` 报告；`GET /api/report-copilot/reports/{draftId}/markdown` 只导出 `CONFIRMED` 草稿。确认或取消通过 `/reports/{draftId}/confirm`、`/cancel` 并且只接受服务端生成的 token。每次预览均由服务端生成来源 ID，并针对脱敏内容和元数据计算 SHA-256 哈希。指标亮点必须与其引用指标的名称、值、单位严格一致；事实项必须引用本次来源，AI 建议与来源行动项分离，模型输出也会再次脱敏。Markdown 由服务端根据结构化内容渲染并转义模型文本，确认不触发任何外部发布。
