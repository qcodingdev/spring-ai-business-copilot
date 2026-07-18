package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;

import java.util.Map;
import java.util.stream.Collectors;

/** Builds the evidence-only prompt context used for structured report generation. */
public class ReportPromptContextFactory {

    public Map<String, String> create(ReportRequestPreparationService.ReportRequestPreview preview) {
        return Map.of(
                "reportType", preview.reportType().name(),
                "periodStart", preview.period().periodStart().toString(),
                "periodEnd", preview.period().periodEnd().toString(),
                "title", preview.title(),
                "templateId", preview.templateId(),
                "templateVersion", preview.templateVersion(),
                "evidencePack", formatEvidencePack(preview));
    }

    private String formatEvidencePack(ReportRequestPreparationService.ReportRequestPreview preview) {
        return preview.sources().stream().map(this::formatSource).collect(Collectors.joining("\n\n---\n\n"));
    }

    private String formatSource(ReportSource source) {
        return "sourceId=" + source.sourceId()
                + "\ntype=" + source.sourceType()
                + "\ntitle=" + source.title()
                + "\nsnapshotId=" + source.snapshotId()
                + "\nproviderId=" + source.providerId()
                + "\nsourceVersion=" + source.sourceVersion()
                + "\nobservedAt=" + source.observedAt()
                + "\ntimezone=" + source.sourceTimezone()
                + "\nunit=" + source.sourceUnit()
                + "\nvalidUntil=" + source.validUntil()
                + "\nfreshness=" + source.freshness()
                + "\ncontent:\n" + source.sanitizedContent()
                + "\nmetadata=" + source.attributes();
    }
}
