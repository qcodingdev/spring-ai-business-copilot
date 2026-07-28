package dev.qcoding.businesscopilot.reportcopilot.export;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportItem;
import org.apache.pdfbox.Loader;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportOfficeExportServiceTest {

    @BeforeAll
    static void useHeadlessRendering() {
        System.setProperty("java.awt.headless", "true");
    }

    private final ReportDraftRepository repository = mock(ReportDraftRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ReportOfficeExportService service = new ReportOfficeExportService(
            repository,
            () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
            new DefaultObjectAccessPolicy(),
            jdbcTemplate);

    @Test
    void exportsConfirmedDraftToDocxPdfAndPptxAndAuditsHashes() throws Exception {
        when(repository.findById(10L)).thenReturn(Optional.of(draft()));

        byte[] docx = service.exportDocx(10L);
        byte[] pdf = service.exportPdf(10L);
        byte[] pptx = service.exportPptx(10L);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             var pdfDocument = Loader.loadPDF(pdf);
             XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertThat(document.getParagraphs()).anySatisfy(paragraph ->
                    assertThat(paragraph.getText()).contains("执行摘要"));
            assertThat(pdfDocument.getNumberOfPages()).isPositive();
            assertThat(slides.getSlides()).hasSizeGreaterThan(1);
        }
        verify(jdbcTemplate, atLeast(3)).update(
                matches("(?s).*INSERT INTO report_export_audit.*"),
                eq(10L), org.mockito.ArgumentMatchers.anyString(), eq("operator-1"),
                matches("[0-9a-f]{64}"));
    }

    private ReportDraft draft() {
        LlmReportOutput content = new LlmReportOutput(
                "本周经营稳定",
                List.of("source-1"),
                List.of(),
                List.of(new ReportItem("完成企业接入", List.of("source-1"))),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return new ReportDraft(
                10L, 20L, content, ReportDraftStatus.CONFIRMED,
                null, null, null, "operator-1", "operator-1",
                Instant.now().plusSeconds(60), Instant.now(), Instant.now());
    }
}
