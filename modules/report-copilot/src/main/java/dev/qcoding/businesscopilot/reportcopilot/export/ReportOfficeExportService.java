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
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.jdbc.core.JdbcTemplate;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var title = document.createParagraph();
            title.setStyle("Title");
            title.createRun().setText("业务报告 " + draftId);
            for (Section section : sections(draft)) {
                var heading = document.createParagraph();
                heading.setStyle("Heading1");
                heading.createRun().setText(section.title());
                for (String line : section.lines()) {
                    var paragraph = document.createParagraph();
                    paragraph.setStyle("ListBullet");
                    paragraph.createRun().setText(line);
                }
            }
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
        try (XMLSlideShow slides = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slides.setPageSize(new java.awt.Dimension(1280, 720));
            XSLFSlide cover = slides.createSlide();
            addTextBox(cover, "业务报告 " + draftId, 80, 220, 1120, 120, 36, true);
            for (Section section : sections(draft)) {
                List<String> lines = section.lines().isEmpty() ? List.of("暂无内容") : section.lines();
                for (int offset = 0; offset < lines.size(); offset += 8) {
                    XSLFSlide slide = slides.createSlide();
                    addTextBox(slide, section.title(), 60, 35, 1160, 80, 28, true);
                    addTextBox(slide, String.join("\n", lines.subList(
                                    offset, Math.min(offset + 8, lines.size()))),
                            80, 130, 1100, 520, 20, false);
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
        List<String> lines = new ArrayList<>();
        lines.add("业务报告 " + draftId);
        for (Section section : sections(draft)) {
            lines.add("");
            lines.add(section.title());
            section.lines().forEach(line -> lines.add("• " + line));
        }
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int offset = 0; offset < lines.size(); offset += 28) {
                BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(Color.BLACK);
                graphics.setFont(new Font("SansSerif", Font.PLAIN, 30));
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int y = 90;
                for (String line : lines.subList(offset, Math.min(offset + 28, lines.size()))) {
                    graphics.drawString(bound(line, 62), 80, y);
                    y += 56;
                }
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
        sections.add(new Section("来源行动项", content.actionItems().stream()
                .map(item -> item.text()).toList()));
        sections.add(new Section("AI 建议", content.suggestions().stream()
                .map(item -> item.text()).toList()));
        return sections;
    }

    private void addTextBox(XSLFSlide slide, String text, double x, double y,
                            double width, double height, double fontSize, boolean bold) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new java.awt.geom.Rectangle2D.Double(x, y, width, height));
        box.setText(text);
        box.getTextParagraphs().forEach(paragraph -> {
            paragraph.setTextAlign(TextParagraph.TextAlign.LEFT);
            paragraph.getTextRuns().forEach(run -> {
                run.setFontFamily("Microsoft YaHei");
                run.setFontSize(fontSize);
                run.setBold(bold);
            });
        });
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

    private record Section(String title, List<String> lines) { }
}
