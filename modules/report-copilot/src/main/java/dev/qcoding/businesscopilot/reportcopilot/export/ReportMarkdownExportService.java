package dev.qcoding.businesscopilot.reportcopilot.export;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportActionItem;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportItem;

import java.util.List;

/** Renders a confirmed structured draft to Markdown without trusting model-provided Markdown syntax. */
public class ReportMarkdownExportService {

    private final ReportDraftRepository draftRepository;
    private final ReportCopilotProperties properties;
    private final ReportAuditService auditService;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;

    public ReportMarkdownExportService(ReportDraftRepository draftRepository, ReportCopilotProperties properties,
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
        if (!properties.markdownExportEnabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Markdown export is disabled.");
        }
        ReportDraft draft = draftRepository.findById(draftId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND));
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, ObjectAction.EXPORT,
                draft.ownerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (draft.status() != ReportDraftStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Only CONFIRMED report drafts can be exported.");
        }
        String markdown = render(draft);
        auditService.recordRequired(new ReportAuditLog(
                draft.requestId(), draft.id(), "EXPORTED", 0, "", null,
                ReportDraftStatus.CONFIRMED.name(), null, null,
                draft.ownerActorId(), actor.actorId(), null, null,
                null, null, null, null, null, null, null, null));
        return markdown;
    }

    private String render(ReportDraft draft) {
        var content = draft.content();
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(escape(draft.id() == null ? "Report" : "Report " + draft.id())).append("\n\n");
        markdown.append("## Executive Summary\n\n").append(escape(content.executiveSummary())).append("\n");
        appendSources(markdown, content.executiveSummarySourceIds());
        appendMetrics(markdown, content.metricHighlights());
        appendItems(markdown, "Completed Items", content.completedItems());
        appendItems(markdown, "Risks and Blockers", content.risks());
        appendActions(markdown, "Source Actions", content.actionItems());
        appendActions(markdown, "AI Suggestions", content.suggestions());
        return markdown.toString();
    }

    private void appendMetrics(StringBuilder markdown, List<dev.qcoding.businesscopilot.reportcopilot.generation.MetricHighlight> metrics) {
        if (metrics.isEmpty()) return;
        markdown.append("\n## Metric Highlights\n\n");
        for (var metric : metrics) {
            markdown.append("- **").append(escape(metric.metricName())).append("**: ")
                    .append(escape(metric.metricValue())).append(" ").append(escape(metric.unit()))
                    .append(" - ").append(escape(metric.summary())).append("\n");
            appendSources(markdown, metric.sourceIds());
        }
    }

    private void appendItems(StringBuilder markdown, String heading, List<ReportItem> items) {
        if (items.isEmpty()) return;
        markdown.append("\n## ").append(heading).append("\n\n");
        for (ReportItem item : items) {
            markdown.append("- ").append(escape(item.text())).append("\n");
            appendSources(markdown, item.sourceIds());
        }
    }

    private void appendActions(StringBuilder markdown, String heading, List<ReportActionItem> actions) {
        if (actions.isEmpty()) return;
        markdown.append("\n## ").append(heading).append("\n\n");
        for (ReportActionItem action : actions) {
            markdown.append("- ").append(escape(action.text())).append("\n");
            appendSources(markdown, action.sourceIds());
        }
    }

    private void appendSources(StringBuilder markdown, List<String> sourceIds) {
        if (!sourceIds.isEmpty()) {
            markdown.append("  - Sources: ").append(sourceIds.stream().map(this::escape).reduce((a, b) -> a + ", " + b).orElse(""))
                    .append("\n");
        }
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*")
                .replace("_", "\\_").replace("[", "\\[").replace("]", "\\]")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
