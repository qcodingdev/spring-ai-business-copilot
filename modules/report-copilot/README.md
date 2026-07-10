# report-copilot

English | [简体中文](#简体中文)

## English

Source-grounded weekly report assistant. The initial feature exposes a sanitized evidence pack of fictional metrics, task progress, and meeting notes at `GET /api/report-copilot/sample-sources`.

Every preview assigns server-generated source IDs and a SHA-256 hash based on sanitized content. This provides the evidence boundary that later structured generation, review, confirmation, and Markdown export will reuse.

### Current boundary

- No model call or report publication.
- No user-provided or model-generated SQL.
- No external task, meeting, or BI integration.

### Test

```bash
../../mvnw -f ../../pom.xml -pl modules/report-copilot -am test
```

## 简体中文

Report Copilot 当前提供虚构指标、任务和会议记录的脱敏来源预览，接口为 `GET /api/report-copilot/sample-sources`。每次预览均由服务端生成来源 ID，并针对脱敏内容计算 SHA-256 哈希，为后续结构化周报、人工确认和 Markdown 导出建立证据边界。
