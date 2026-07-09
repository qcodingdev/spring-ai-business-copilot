package dev.qcoding.businesscopilot.knowledgecopilot.document;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for uploading a knowledge document.
 *
 * <p>文档上传请求。调用方传入原始文件名和文本内容，由服务端决定解析器和处理逻辑。</p>
 *
 * @param fileName original file name, used for format detection and metadata
 * @param content  raw text content of the document
 * @param category optional business category tag
 */
public record DocumentUploadRequest(
        @NotBlank String fileName,
        @NotBlank String content,
        String category) {
}
