package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 将有界 CSV/JSON 业务数据转换为不可变来源快照。 */
public class ReportSourceImportService {

    private final ObjectMapper objectMapper;
    private final ReportSourceNormalizer normalizer;
    private final ReportCopilotProperties properties;

    public ReportSourceImportService(ObjectMapper objectMapper,
                                     ReportSourceNormalizer normalizer,
                                     ReportCopilotProperties properties) {
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
        this.properties = properties;
    }

    public ImportPreview preview(String fileName, String contentType, byte[] content) {
        List<RawReportSource> rawSources = parse(fileName, contentType, content);
        return new ImportPreview(Instant.now(), fileName, format(fileName, contentType),
                normalizer.normalize(rawSources));
    }

    public List<RawReportSource> parse(String fileName, String contentType, byte[] content) {
        byte[] safeContent = content == null ? new byte[0] : content;
        if (safeContent.length == 0 || safeContent.length > properties.maxImportBytes()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源文件为空或超过大小限制");
        }
        return switch (format(fileName, contentType)) {
            case "json" -> parseJson(safeContent);
            case "csv" -> parseCsv(new String(safeContent, StandardCharsets.UTF_8));
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "仅支持 CSV 和 JSON 报告来源文件");
        };
    }

    private List<RawReportSource> parseJson(byte[] content) {
        try {
            ImportRow[] rows = objectMapper.readValue(content, ImportRow[].class);
            return rowsToSources(rows == null ? List.of() : List.of(rows));
        } catch (JacksonException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "JSON 报告来源文件格式无效");
        }
    }

    private List<RawReportSource> parseCsv(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = normalized.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "CSV 报告来源必须包含表头和至少一行数据");
        }
        List<String> headers = parseCsvLine(lines.getFirst()).stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
        List<ImportRow> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < lines.size(); rowIndex++) {
            List<String> values = parseCsvLine(lines.get(rowIndex));
            if (values.size() != headers.size()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "CSV 第 " + (rowIndex + 1) + " 行的列数与表头不一致");
            }
            Map<String, String> row = new LinkedHashMap<>();
            Map<String, String> attributes = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String header = headers.get(column);
                if (header.startsWith("attr.")) {
                    attributes.put(header.substring("attr.".length()), values.get(column));
                } else {
                    row.put(header, values.get(column));
                }
            }
            rows.add(new ImportRow(
                    row.get("sourcetype"), row.get("title"), row.get("content"), attributes,
                    row.get("providerid"), row.get("sourceversion"), row.get("observedat"),
                    row.get("sourcetimezone"), row.get("sourceunit"), row.get("validuntil")));
        }
        return rowsToSources(rows);
    }

    private List<RawReportSource> rowsToSources(List<ImportRow> rows) {
        if (rows.isEmpty() || rows.size() > properties.maxSourceCount()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源文件的数据行数量不符合限制");
        }
        return rows.stream().map(this::toSource).toList();
    }

    private RawReportSource toSource(ImportRow row) {
        try {
            return new RawReportSource(
                    ReportSourceType.valueOf(required(row.sourceType(), "sourceType").toUpperCase(Locale.ROOT)),
                    required(row.title(), "title"),
                    required(row.content(), "content"),
                    row.attributes(),
                    row.providerId(),
                    row.sourceVersion(),
                    parseInstant(row.observedAt(), "observedAt"),
                    row.sourceTimezone(),
                    row.sourceUnit(),
                    parseInstant(row.validUntil(), "validUntil"));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源中存在无效的来源类型或时间");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告来源缺少必填字段：" + field);
        }
        return value.trim();
    }

    private Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(field, ex);
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char currentChar = line.charAt(index);
            if (currentChar == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (currentChar == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(currentChar);
            }
        }
        if (quoted) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "CSV 报告来源存在未闭合的引号");
        }
        values.add(current.toString());
        return values;
    }

    private String format(String fileName, String contentType) {
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".json") || normalizedType.contains("json")) {
            return "json";
        }
        if (normalizedName.endsWith(".csv") || normalizedType.contains("csv")) {
            return "csv";
        }
        return "unsupported";
    }

    public record ImportPreview(Instant importedAt, String fileName, String format,
                                List<ReportSource> sources) {
    }

    private record ImportRow(String sourceType, String title, String content,
                             Map<String, String> attributes, String providerId,
                             String sourceVersion, String observedAt, String sourceTimezone,
                             String sourceUnit, String validUntil) {
        private ImportRow {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
