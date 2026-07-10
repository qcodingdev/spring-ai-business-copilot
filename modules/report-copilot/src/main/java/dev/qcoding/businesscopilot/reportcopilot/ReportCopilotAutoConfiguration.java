package dev.qcoding.businesscopilot.reportcopilot;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceNormalizer;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import dev.qcoding.businesscopilot.reportcopilot.source.SampleReportDataProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Report Copilot module.
 *
 * <p>The initial vertical slice exposes normalized fictional source data only. Report generation,
 * draft state transitions, and export are added in later slices.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.report-copilot", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ReportCopilotProperties.class)
public class ReportCopilotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SampleReportDataProvider sampleReportDataProvider() {
        return new SampleReportDataProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportSourceNormalizer reportSourceNormalizer(SensitiveTextMasker sensitiveTextMasker,
                                                         ReportCopilotProperties properties) {
        return new ReportSourceNormalizer(sensitiveTextMasker, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportSourcePreviewService reportSourcePreviewService(SampleReportDataProvider dataProvider,
                                                                  ReportSourceNormalizer normalizer) {
        return new ReportSourcePreviewService(dataProvider, normalizer);
    }
}
