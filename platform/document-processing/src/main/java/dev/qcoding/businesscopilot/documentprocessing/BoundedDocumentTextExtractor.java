package dev.qcoding.businesscopilot.documentprocessing;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** 基于 PDFBox/POI 的受限文档提取器，明确限制字节数、页数和字符数。 */
public class BoundedDocumentTextExtractor implements DocumentTextExtractor {

    private final DocumentExtractionProperties properties;

    public BoundedDocumentTextExtractor(DocumentExtractionProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExtractedDocument extract(String fileName, String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY);
        }
        if (content.length > properties.maxFileBytes()) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "文档超过配置的文件大小限制。");
        }
        DocumentFormat format = DocumentFormat.detect(fileName, contentType)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED));

        String text = switch (format) {
            case TEXT, MARKDOWN -> new String(content, StandardCharsets.UTF_8);
            case PDF -> extractPdf(content);
            case DOCX -> extractDocx(content);
        };
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY, "文档中没有提取到可用文本。");
        }
        if (normalized.length() > properties.maxExtractedCharacters()) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "文档解析后的文本超过配置字符数限制。");
        }
        return new ExtractedDocument(format, normalized, normalized.length());
    }

    private String extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                        "暂不支持加密 PDF 文档。");
            }
            if (document.getNumberOfPages() > properties.maxPdfPages()) {
                throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                        "PDF 超过配置的页数限制。");
            }
            return new PDFTextStripper().getText(document);
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                    "PDF 文档解析失败，请确认文件未损坏且包含可提取文本。");
        }
    }

    private String extractDocx(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            String paragraphs = document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n"));
            String tables = document.getTables().stream()
                    .map(XWPFTable::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n"));
            return paragraphs + (paragraphs.isBlank() || tables.isBlank() ? "" : "\n") + tables;
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                    "DOCX 文档解析失败，请确认文件未损坏。");
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }
}
