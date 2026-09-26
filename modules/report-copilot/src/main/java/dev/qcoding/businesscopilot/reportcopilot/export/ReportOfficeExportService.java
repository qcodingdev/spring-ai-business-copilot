package dev.qcoding.businesscopilot.reportcopilot.export;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableWidthType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.jdbc.core.JdbcTemplate;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 已确认报告的 DOCX、PDF、PPTX 确定性导出。 */
public class ReportOfficeExportService {

    private final ReportDraftRepository draftRepository;
    private final CurrentActorProvider actorProvider;
    private final ObjectAccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public ReportOfficeExportService(
            ReportDraftRepository draftRepository,
            CurrentActorProvider actorProvider,
            ObjectAccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.draftRepository = draftRepository;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.jdbcTemplate = jdbcTemplate;
    }

    public byte[] exportDocx(long draftId) {
        ReportDraft draft = requireExportable(draftId);
        ReportMetadata metadata = metadata(draft);
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sectionProperties = document.getDocument().getBody().isSetSectPr()
                    ? document.getDocument().getBody().getSectPr()
                    : document.getDocument().getBody().addNewSectPr();
            var margins = sectionProperties.addNewPgMar();
            margins.setTop(BigInteger.valueOf(900));
            margins.setBottom(BigInteger.valueOf(900));
            margins.setLeft(BigInteger.valueOf(1080));
            margins.setRight(BigInteger.valueOf(1080));

            var title = document.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            title.setSpacingBefore(520);
            title.setSpacingAfter(180);
            styleRun(title.createRun(), metadata.title(), 26, true, "26247A");
            var subtitle = document.createParagraph();
            subtitle.setAlignment(ParagraphAlignment.CENTER);
            subtitle.setSpacingAfter(520);
            styleRun(subtitle.createRun(), metadata.periodLabel() + "  ·  " + metadata.typeLabel(),
                    10, false, "667085");

            var summaryTitle = document.createParagraph();
            summaryTitle.setSpacingAfter(100);
            styleRun(summaryTitle.createRun(), "执行摘要", 15, true, "26247A");
            var summaryTable = document.createTable(1, 1);
            summaryTable.setWidth("100%");
            summaryTable.setWidthType(TableWidthType.PCT);
            var summaryCell = summaryTable.getRow(0).getCell(0);
            summaryCell.getCTTc().addNewTcPr().addNewShd().setFill("F3F4FF");
            summaryCell.removeParagraph(0);
            var summary = summaryCell.addParagraph();
            summary.setSpacingAfter(80);
            styleRun(summary.createRun(), value(draft.content().executiveSummary()), 11, false, "20243A");

            for (Section section : sections(draft).stream().skip(1).toList()) {
                var heading = document.createParagraph();
                heading.setSpacingBefore(300);
                heading.setSpacingAfter(100);
                styleRun(heading.createRun(), section.title(), 15, true, "26247A");
                List<String> lines = section.lines().isEmpty() ? List.of("暂无内容") : section.lines();
                for (String line : lines) {
                    var paragraph = document.createParagraph();
                    paragraph.setIndentationLeft(300);
                    paragraph.setFirstLineIndent(-180);
                    paragraph.setSpacingAfter(80);
                    styleRun(paragraph.createRun(), "•  " + line, 10, false, "344054");
                }
            }
            XWPFParagraph footer = document.createFooter(HeaderFooterType.DEFAULT).createParagraph();
            footer.setAlignment(ParagraphAlignment.CENTER);
            styleRun(footer.createRun(), "Spring AI Business Copilot  ·  报告编号 #" + draftId,
                    8, false, "98A2B3");
            document.write(output);
            byte[] bytes = output.toByteArray();
            audit(draftId, "DOCX", bytes);
            return bytes;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("生成 DOCX 报告失败", ex);
        }
    }

    public byte[] exportPptx(long draftId) {
        ReportDraft draft = requireExportable(draftId);
        ReportMetadata metadata = metadata(draft);
        try (XMLSlideShow slides = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slides.setPageSize(new java.awt.Dimension(1280, 720));
            XSLFSlide cover = slides.createSlide();
            addRectangle(cover, 0, 0, 1280, 720, new Color(38, 36, 122));
            addRectangle(cover, 70, 175, 12, 230, new Color(132, 137, 255));
            addTextBox(cover, metadata.title(), 115, 195, 1050, 130, 40, true, Color.WHITE);
            addTextBox(cover, metadata.periodLabel() + "  ·  " + metadata.typeLabel(),
                    118, 340, 1000, 55, 18, false, new Color(220, 221, 255));
            addTextBox(cover, "Spring AI Business Copilot  ·  #" + draftId,
                    118, 625, 1000, 35, 12, false, new Color(184, 186, 232));
            for (Section section : sections(draft)) {
                List<String> lines = section.lines().isEmpty() ? List.of("暂无内容") : section.lines();
                for (int offset = 0; offset < lines.size(); offset += 7) {
                    XSLFSlide slide = slides.createSlide();
                    addRectangle(slide, 0, 0, 1280, 18, new Color(91, 92, 240));
                    addTextBox(slide, section.title(), 70, 55, 1110, 65, 28, true,
                            new Color(38, 36, 122));
                    addTextBox(slide, bulletText(lines.subList(offset, Math.min(offset + 7, lines.size()))),
                            90, 145, 1080, 465, 19, false, new Color(52, 64, 84));
                    addTextBox(slide, metadata.title() + "  ·  " + (slides.getSlides().size()),
                            70, 660, 1120, 25, 10, false, new Color(102, 112, 133));
                }
            }
            slides.write(output);
            byte[] bytes = output.toByteArray();
            audit(draftId, "PPTX", bytes);
            return bytes;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("生成 PPTX 报告失败", ex);
        }
    }

    public byte[] exportPdf(long draftId) {
        ReportDraft draft = requireExportable(draftId);
        ReportMetadata metadata = metadata(draft);
        List<PdfLine> lines = new ArrayList<>();
        lines.add(new PdfLine(metadata.title(), PdfLineType.TITLE));
        lines.add(new PdfLine(metadata.periodLabel() + "  ·  " + metadata.typeLabel(), PdfLineType.META));
        for (Section section : sections(draft)) {
            lines.add(new PdfLine(section.title(), PdfLineType.HEADING));
            List<String> sectionLines = section.lines().isEmpty() ? List.of("暂无内容") : section.lines();
            sectionLines.forEach(line -> wrap(line, 47).forEach(part ->
                    lines.add(new PdfLine(part, PdfLineType.BODY))));
        }
        List<List<PdfLine>> pages = paginate(lines, 1420);
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setColor(new Color(38, 36, 122));
                graphics.fillRect(0, 0, 24, image.getHeight());
                graphics.setColor(new Color(91, 92, 240));
                graphics.fillRect(24, 0, image.getWidth() - 24, 14);
                int y = 105;
                for (PdfLine line : pages.get(pageIndex)) {
                    y += drawPdfLine(graphics, line, y);
                }
                graphics.setColor(new Color(102, 112, 133));
                graphics.setFont(new Font("SansSerif", Font.PLAIN, 20));
                graphics.drawString("Spring AI Business Copilot  ·  #" + draftId,
                        82, image.getHeight() - 58);
                graphics.drawString((pageIndex + 1) + " / " + pages.size(),
                        image.getWidth() - 165, image.getHeight() - 58);
                graphics.dispose();
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                var pdfImage = LosslessFactory.createFromImage(document, image);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(pdfImage, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            document.save(output);
            byte[] bytes = output.toByteArray();
            audit(draftId, "PDF", bytes);
            return bytes;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("生成 PDF 报告失败", ex);
        }
    }

    private ReportDraft requireExportable(long draftId) {
        ReportDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, ObjectAction.EXPORT, draft.ownerActorId(), null, false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (draft.status() != ReportDraftStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "只有已确认的报告草稿可以导出");
        }
        return draft;
    }

    private List<Section> sections(ReportDraft draft) {
        var content = draft.content();
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("执行摘要", List.of(value(content.executiveSummary()))));
        sections.add(new Section("指标亮点", content.metricHighlights().stream()
                .map(metric -> metric.metricName() + "：" + metric.metricValue()
                        + " " + value(metric.unit()) + "；" + value(metric.summary())).toList()));
        sections.add(new Section("已完成事项", content.completedItems().stream()
                .map(item -> item.text()).toList()));
        sections.add(new Section("风险与阻塞", content.risks().stream()
                .map(item -> item.text()).toList()));
        sections.add(new Section("后续行动", content.actionItems().stream()
                .map(item -> item.text()).toList()));
        sections.add(new Section("AI 建议", content.suggestions().stream()
                .map(item -> item.text()).toList()));
        return sections;
    }

    private void addTextBox(XSLFSlide slide, String text, double x, double y,
                            double width, double height, double fontSize, boolean bold,
                            Color color) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new java.awt.geom.Rectangle2D.Double(x, y, width, height));
        box.setText(text);
        box.getTextParagraphs().forEach(paragraph -> {
            paragraph.setTextAlign(TextParagraph.TextAlign.LEFT);
            paragraph.getTextRuns().forEach(run -> {
                run.setFontFamily("Microsoft YaHei");
                run.setFontSize(fontSize);
                run.setBold(bold);
                run.setFontColor(color);
            });
        });
    }

    private void addRectangle(XSLFSlide slide, double x, double y,
                              double width, double height, Color color) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new java.awt.geom.Rectangle2D.Double(x, y, width, height));
        shape.setFillColor(color);
        shape.setLineColor(color);
    }

    private String bulletText(List<String> lines) {
        return lines.stream().map(line -> "•  " + line).reduce((left, right) -> left + "\n\n" + right)
                .orElse("暂无内容");
    }

    private void styleRun(XWPFRun run, String text, int size, boolean bold, String color) {
        run.setText(text);
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(size);
        run.setBold(bold);
        run.setColor(color);
    }

    private ReportMetadata metadata(ReportDraft draft) {
        List<ReportMetadata> rows = jdbcTemplate.query("""
                SELECT title, report_type, period_start, period_end, period_timezone
                FROM report_requests WHERE id = ?
                """, (rs, rowNum) -> new ReportMetadata(
                value(rs.getString("title")), value(rs.getString("report_type")),
                value(rs.getString("period_start")), value(rs.getString("period_end")), value(rs.getString("period_timezone"))),
                draft.requestId());
        if (rows == null || rows.isEmpty()) {
            return new ReportMetadata("企业经营报告", "BUSINESS", "", "", "");
        }
        return rows.getFirst();
    }

    private List<String> wrap(String input, int width) {
        String normalized = value(input).replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return List.of("暂无内容");
        List<String> result = new ArrayList<>();
        for (int offset = 0; offset < normalized.length(); offset += width) {
            result.add(normalized.substring(offset, Math.min(offset + width, normalized.length())));
        }
        return result;
    }

    private List<List<PdfLine>> paginate(List<PdfLine> lines, int availableHeight) {
        List<List<PdfLine>> pages = new ArrayList<>();
        List<PdfLine> current = new ArrayList<>();
        int used = 0;
        for (PdfLine line : lines) {
            int height = line.type().height;
            if (!current.isEmpty() && used + height > availableHeight) {
                pages.add(List.copyOf(current));
                current.clear();
                used = 0;
            }
            current.add(line);
            used += height;
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        return pages.isEmpty() ? List.of(List.of(new PdfLine("暂无内容", PdfLineType.BODY))) : pages;
    }

    private int drawPdfLine(Graphics2D graphics, PdfLine line, int y) {
        switch (line.type()) {
            case TITLE -> {
                graphics.setColor(new Color(38, 36, 122));
                graphics.setFont(new Font("SansSerif", Font.BOLD, 48));
                graphics.drawString(bound(line.text(), 34), 82, y);
            }
            case META -> {
                graphics.setColor(new Color(102, 112, 133));
                graphics.setFont(new Font("SansSerif", Font.PLAIN, 23));
                graphics.drawString(line.text(), 84, y);
            }
            case HEADING -> {
                graphics.setColor(new Color(91, 92, 240));
                graphics.fillRoundRect(82, y - 34, 10, 42, 6, 6);
                graphics.setColor(new Color(38, 36, 122));
                graphics.setFont(new Font("SansSerif", Font.BOLD, 31));
                graphics.drawString(line.text(), 112, y);
            }
            case BODY -> {
                graphics.setColor(new Color(52, 64, 84));
                graphics.setFont(new Font("SansSerif", Font.PLAIN, 25));
                graphics.drawString("•", 96, y);
                graphics.drawString(line.text(), 126, y);
            }
        }
        return line.type().height;
    }

    private void audit(long draftId, String format, byte[] content) {
        jdbcTemplate.update("""
                INSERT INTO report_export_audit (
                    draft_id, export_format, exported_by, content_hash
                ) VALUES (?, ?, ?, ?)
                """, draftId, format, actorProvider.currentActor().actorId(), sha256(content));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
    }

    private String bound(String value, int max) {
        String normalized = value(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record ReportMetadata(String title, String type, String periodStart, String periodEnd, String timezone) {
        String periodLabel() {
            if (periodStart.isBlank() && periodEnd.isBlank()) return "已确认报告";
            return periodStart + " — " + periodEnd + (timezone.isBlank() ? "" : " (" + timezone + ")");
        }

        String typeLabel() {
            return switch (type) {
                case "TEAM_WEEKLY" -> "团队周报";
                case "BUSINESS_WEEKLY" -> "经营周报";
                case "PROJECT_STATUS" -> "项目进展报告";
                case "INCIDENT_REVIEW" -> "事件复盘报告";
                case "SALES_REVIEW" -> "销售复盘报告";
                default -> "经营报告";
            };
        }
    }

    private record PdfLine(String text, PdfLineType type) { }

    private enum PdfLineType {
        TITLE(82), META(72), HEADING(76), BODY(46);

        private final int height;

        PdfLineType(int height) {
            this.height = height;
        }
    }

    private record Section(String title, List<String> lines) { }
}
