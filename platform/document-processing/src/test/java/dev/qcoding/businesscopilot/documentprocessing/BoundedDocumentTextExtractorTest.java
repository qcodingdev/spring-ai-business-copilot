package dev.qcoding.businesscopilot.documentprocessing;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedDocumentTextExtractorTest {

    private final BoundedDocumentTextExtractor extractor =
            new BoundedDocumentTextExtractor(new DocumentExtractionProperties(1024 * 1024, 10_000, 5));

    @Test
    void extractsUtf8Text() {
        ExtractedDocument document = extractor.extract(
                "guide.md", "text/markdown", "# Guide\nTrusted content".getBytes(StandardCharsets.UTF_8));

        assertThat(document.format()).isEqualTo(DocumentFormat.MARKDOWN);
        assertThat(document.text()).contains("Trusted content");
    }

    @Test
    void extractsPdfText() throws Exception {
        byte[] content;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("PDF business evidence");
                stream.endText();
            }
            pdf.save(output);
            content = output.toByteArray();
        }

        assertThat(extractor.extract("evidence.pdf", "application/pdf", content).text())
                .contains("PDF business evidence");
    }

    @Test
    void extractsDocxText() throws Exception {
        byte[] content;
        try (XWPFDocument docx = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            docx.createParagraph().createRun().setText("DOCX business evidence");
            docx.write(output);
            content = output.toByteArray();
        }

        assertThat(extractor.extract("evidence.docx", null, content).text())
                .contains("DOCX business evidence");
    }

    @Test
    void rejectsOversizedInputBeforeParsing() {
        assertThatThrownBy(() -> extractor.extract("large.txt", "text/plain", new byte[1024 * 1024 + 1]))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("大小限制");
    }
}
