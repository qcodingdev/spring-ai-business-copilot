package dev.qcoding.businesscopilot.reportcopilot.source;

import java.time.Instant;
import java.util.List;

/** Builds a request-ready, source-grounded evidence pack from the demo provider. */
public class ReportSourcePreviewService {

    private final ReportDataProvider dataProvider;
    private final ReportSourceNormalizer normalizer;

    public ReportSourcePreviewService(ReportDataProvider dataProvider, ReportSourceNormalizer normalizer) {
        this.dataProvider = dataProvider;
        this.normalizer = normalizer;
    }

    public ReportSourcePreview preview() {
        List<ReportSource> sources = normalizer.normalize(dataProvider.loadSources());
        return new ReportSourcePreview(Instant.now(), sources);
    }

    public record ReportSourcePreview(Instant generatedAt, List<ReportSource> sources) {
    }
}
