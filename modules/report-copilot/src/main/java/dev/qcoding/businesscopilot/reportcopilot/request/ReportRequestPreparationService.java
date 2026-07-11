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

    public ReportRequestPreparationService(ReportRequestValidator validator,
                                           ReportSourceMapper sourceMapper,
                                           ReportSourceNormalizer sourceNormalizer) {
        this.validator = validator;
        this.sourceMapper = sourceMapper;
        this.sourceNormalizer = sourceNormalizer;
    }

    public ReportRequestPreview prepare(ReportGenerateRequest request) {
        validator.validate(request);
        List<ReportSource> sources = sourceNormalizer.normalize(sourceMapper.map(request));
        return new ReportRequestPreview(request.reportType(), request.period(), request.title().trim(), sources);
    }

    public record ReportRequestPreview(ReportType reportType, ReportPeriod period, String title,
                                       List<ReportSource> sources) {
    }
}
