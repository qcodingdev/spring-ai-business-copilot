# report-copilot

English | [简体中文](#简体中文)

## English

Source-grounded weekly report assistant. `GET /api/report-copilot/sample-sources` exposes a sanitized evidence pack of fictional metrics, task progress, and meeting notes. `POST /api/report-copilot/source-previews` validates and normalizes client-provided metric, task, and meeting evidence without invoking a model.

Every preview assigns server-generated source IDs and a SHA-256 hash based on sanitized content and metadata. This provides the evidence boundary that later structured generation, review, confirmation, and Markdown export will reuse.

### Current boundary

- No model call or report publication.
- No user-provided or model-generated SQL.
- No external task, meeting, or BI integration.

### Test

```bash
../../mvnw -f ../../pom.xml -pl modules/report-copilot -am test
```

## 简体中文

Report Copilot 当前提供两类来源预览：`GET /api/report-copilot/sample-sources` 返回虚构指标、任务和会议记录；`POST /api/report-copilot/source-previews` 校验并归一化客户端提交的指标、任务和会议记录，不会调用模型。每次预览均由服务端生成来源 ID，并针对脱敏内容和元数据计算 SHA-256 哈希，为后续结构化周报、人工确认和 Markdown 导出建立证据边界。
