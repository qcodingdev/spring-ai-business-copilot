package dev.qcoding.businesscopilot.reportcopilot.export;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportActionItem;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportItem;

import java.util.List;

/** 将已确认报告草稿确定性地导出为 HTML。 */
public class ReportHtmlExportService {

    private final ReportDraftRepository draftRepository;
    private final ReportCopilotProperties properties;
    private final ReportAuditService auditService;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;

    public ReportHtmlExportService(ReportDraftRepository draftRepository,
                                   ReportCopilotProperties properties,
                                   ReportAuditService auditService,
                                   CurrentActorProvider actorProvider,
                                   ObjectAccessPolicy accessPolicy) {
        this.draftRepository = draftRepository;
        this.properties = properties;
        this.auditService = auditService;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
    }

    public String export(Long draftId) {
        if (!properties.htmlExportEnabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "HTML 导出功能未启用。");
        }
        ReportDraft draft = draftRepository.findById(draftId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND));
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, ObjectAction.EXPORT, draft.ownerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (draft.status() != ReportDraftStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "只有已确认的报告草稿可以导出。");
        }
        String html = render(draft);
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draft.id(), "EXPORTED_HTML", 0, "", null,
                ReportDraftStatus.CONFIRMED.name(), null, null,
                draft.ownerActorId(), actor.actorId(), null, null,
                null, null, null, null, null, null, null, null));
        return html;
    }

    private String render(ReportDraft draft) {
        var content = draft.content();
        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><title>业务报告</title></head>
                <body>
                """);
        html.append("<main><h1>业务报告 ").append(draft.id()).append("</h1>");
        html.append("<section><h2>执行摘要</h2><p>")
                .append(escape(content.executiveSummary())).append("</p>");
        appendSources(html, content.executiveSummarySourceIds());
        html.append("</section>");
        if (!content.metricHighlights().isEmpty()) {
            html.append("<section><h2>指标亮点</h2><ul>");
            content.metricHighlights().forEach(metric -> {
                html.append("<li><strong>").append(escape(metric.metricName())).append("</strong>: ")
                        .append(escape(metric.metricValue())).append(" ")
                        .append(escape(metric.unit())).append(" - ")
                        .append(escape(metric.summary()));
                appendSources(html, metric.sourceIds());
                html.append("</li>");
            });
            html.append("</ul></section>");
        }
        appendItems(html, "已完成事项", content.completedItems());
        appendItems(html, "风险与阻塞", content.risks());
        appendActions(html, "来源行动项", content.actionItems());
        appendActions(html, "AI 建议", content.suggestions());
        return html.append("</main></body></html>").toString();
    }

    private void appendItems(StringBuilder html, String heading, List<ReportItem> items) {
        if (items.isEmpty()) {
            return;
        }
        html.append("<section><h2>").append(heading).append("</h2><ul>");
        for (ReportItem item : items) {
            html.append("<li>").append(escape(item.text()));
            appendSources(html, item.sourceIds());
            html.append("</li>");
        }
        html.append("</ul></section>");
    }

    private void appendActions(StringBuilder html, String heading, List<ReportActionItem> actions) {
        if (actions.isEmpty()) {
            return;
        }
        html.append("<section><h2>").append(heading).append("</h2><ul>");
        for (ReportActionItem action : actions) {
            html.append("<li>").append(escape(action.text()));
            appendSources(html, action.sourceIds());
            html.append("</li>");
        }
        html.append("</ul></section>");
    }

    private void appendSources(StringBuilder html, List<String> sourceIds) {
        if (!sourceIds.isEmpty()) {
            html.append("<small> 来源：")
                    .append(sourceIds.stream().map(this::escape).reduce((left, right) -> left + "、" + right).orElse(""))
                    .append("</small>");
        }
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
