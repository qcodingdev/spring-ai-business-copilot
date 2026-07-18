package dev.qcoding.businesscopilot.documentprocessing;

import java.util.Locale;
import java.util.Optional;

/** 可转换为受限纯文本的文件格式。 */
public enum DocumentFormat {
    TEXT,
    MARKDOWN,
    PDF,
    DOCX;

    public static Optional<DocumentFormat> detect(String fileName, String contentType) {
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".md") || normalizedName.endsWith(".markdown")) {
            return Optional.of(MARKDOWN);
        }
        if (normalizedName.endsWith(".txt")) {
            return Optional.of(TEXT);
        }
        if (normalizedName.endsWith(".pdf")) {
            return Optional.of(PDF);
        }
        if (normalizedName.endsWith(".docx")) {
            return Optional.of(DOCX);
        }

        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "text/plain" -> Optional.of(TEXT);
            case "text/markdown" -> Optional.of(MARKDOWN);
            case "application/pdf" -> Optional.of(PDF);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    Optional.of(DOCX);
            default -> Optional.empty();
        };
    }
}
