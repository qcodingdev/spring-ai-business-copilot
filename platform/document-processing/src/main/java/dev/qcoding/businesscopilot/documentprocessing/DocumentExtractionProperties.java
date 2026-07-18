package dev.qcoding.businesscopilot.documentprocessing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 文档文本进入业务模块前应用的资源限制。 */
@ConfigurationProperties(prefix = "business-copilot.document-processing")
public record DocumentExtractionProperties(
        long maxFileBytes,
        int maxExtractedCharacters,
        int maxPdfPages) {

    public DocumentExtractionProperties {
        if (maxFileBytes <= 0) {
            maxFileBytes = 5L * 1024 * 1024;
        }
        if (maxExtractedCharacters <= 0) {
            maxExtractedCharacters = 100_000;
        }
        if (maxPdfPages <= 0) {
            maxPdfPages = 100;
        }
    }
}
