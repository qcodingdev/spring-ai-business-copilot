package dev.qcoding.businesscopilot.reportcopilot.request;

import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceMapper;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceNormalizer;

import java.util.List;

/** Prepares a validated, sanitized evidence pack for future structured report generation. */
public class ReportRequestPreparationService {

    private final ReportRequestValidator validator;
    private final ReportSourceMapper sourceMapper;
    private final ReportSourceNormalizer sourceNormalizer;
    private final dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties properties;

    public ReportRequestPreparationService(ReportRequestValidator validator,
                                           ReportSourceMapper sourceMapper,
                                           ReportSourceNormalizer sourceNormalizer) {
        this(validator, sourceMapper, sourceNormalizer, null);
    }

    public ReportRequestPreparationService(ReportRequestValidator validator,
                                           ReportSourceMapper sourceMapper,
                                           ReportSourceNormalizer sourceNormalizer,
                                           dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties properties) {
        this.validator = validator;
        this.sourceMapper = sourceMapper;
        this.sourceNormalizer = sourceNormalizer;
        this.properties = properties;
    }

    public ReportRequestPreview prepare(ReportGenerateRequest request) {
        validator.validate(request);
        List<ReportSource> sources = sourceNormalizer.normalize(sourceMapper.map(request));
        String templateId = valueOrDefault(request.templateId(),
                properties == null ? "evidence-weekly" : properties.defaultTemplateId());
        String templateVersion = valueOrDefault(request.templateVersion(),
                properties == null ? "2.0" : properties.defaultTemplateVersion());
        return new ReportRequestPreview(request.reportType(), request.period(), request.title().trim(),
                sources, templateId, templateVersion);
    }

    public record ReportRequestPreview(ReportType reportType, ReportPeriod period, String title,
                                       List<ReportSource> sources, String templateId, String templateVersion) {

        public ReportRequestPreview(ReportType reportType, ReportPeriod period, String title,
                                    List<ReportSource> sources) {
            this(reportType, period, title, sources, "evidence-weekly", "2.0");
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
