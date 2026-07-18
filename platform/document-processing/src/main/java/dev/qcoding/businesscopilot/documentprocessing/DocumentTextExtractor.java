package dev.qcoding.businesscopilot.documentprocessing;

/** 从受支持且大小受限的业务文档中提取纯文本。 */
public interface DocumentTextExtractor {

    ExtractedDocument extract(String fileName, String contentType, byte[] content);
}
