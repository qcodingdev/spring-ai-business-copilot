package dev.qcoding.businesscopilot.documentprocessing;

/** 从单个受支持业务文档中提取的安全文本。 */
public record ExtractedDocument(
        DocumentFormat format,
        String text,
        int characterCount) {
}
