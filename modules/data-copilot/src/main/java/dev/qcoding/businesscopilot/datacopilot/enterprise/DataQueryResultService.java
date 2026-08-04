package dev.qcoding.businesscopilot.datacopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationResponse;
import dev.qcoding.businesscopilot.datacopilot.query.QueryColumn;
import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 保存脱敏查询结果，并提供受控导出和 Report 交接。 */
public class DataQueryResultService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actorProvider;
    private final Duration retention;

    public DataQueryResultService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                  CurrentActorProvider actorProvider, Duration retention) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.actorProvider = actorProvider;
        this.retention = retention;
    }

    public long save(String candidateId, QueryResultTable table, ResultExplanationResponse explanation) {
        String actorId = actorProvider.currentActor().actorId();
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO data_query_results (
                        candidate_id, owner_actor_id, columns_json, rows_json, row_count,
                        truncated, explanation_json, expires_at
                    ) VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (candidate_id) DO UPDATE SET
                        columns_json = EXCLUDED.columns_json,
                        rows_json = EXCLUDED.rows_json,
                        row_count = EXCLUDED.row_count,
                        truncated = EXCLUDED.truncated,
                        explanation_json = EXCLUDED.explanation_json,
                        expires_at = EXCLUDED.expires_at
                    RETURNING id
                    """, new String[]{"id"});
            statement.setString(1, candidateId);
            statement.setString(2, actorId);
            statement.setString(3, json(table.columns()));
            statement.setString(4, json(table.rows().stream().map(row -> row.values()).toList()));
            statement.setInt(5, table.rowCount());
            statement.setBoolean(6, table.truncated());
            statement.setString(7, json(explanation));
            statement.setTimestamp(8, Timestamp.from(Instant.now().plus(retention)));
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public byte[] exportCsv(long resultId) {
        StoredResult result = requireOwned(resultId);
        StringBuilder csv = new StringBuilder();
        csv.append(result.columns().stream().map(QueryColumn::name).map(this::csvCell)
                .collect(java.util.stream.Collectors.joining(","))).append('\n');
        for (Map<String, Object> row : result.rows()) {
            csv.append(result.columns().stream()
                    .map(column -> csvCell(String.valueOf(row.getOrDefault(column.name(), ""))))
                    .collect(java.util.stream.Collectors.joining(","))).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportXlsx(long resultId) {
        StoredResult result = requireOwned(resultId);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("查询结果");
            Row header = sheet.createRow(0);
            for (int i = 0; i < result.columns().size(); i++) {
                header.createCell(i).setCellValue(result.columns().get(i).name());
            }
            for (int rowIndex = 0; rowIndex < result.rows().size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                Map<String, Object> values = result.rows().get(rowIndex);
                for (int columnIndex = 0; columnIndex < result.columns().size(); columnIndex++) {
                    Cell cell = row.createCell(columnIndex);
                    Object value = values.get(result.columns().get(columnIndex).name());
                    cell.setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("生成 XLSX 查询结果失败", ex);
        }
    }

    public Handoff createReportHandoff(long resultId, String title) {
        requireOwned(resultId);
        String actorId = actorProvider.currentActor().actorId();
        String reference = "data-result:" + UUID.randomUUID();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO data_report_handoffs (
                    query_result_id, owner_actor_id, title, source_reference
                ) VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, resultId, actorId, title.trim(), reference);
        return new Handoff(id, resultId, reference, "READY");
    }

    /** 返回当前操作者仍可查看的结果快照，供执行记录和交接选择使用。 */
    public List<ResultSummary> listOwned(int page, int size) {
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.query("""
                SELECT id, candidate_id, row_count, truncated, expires_at, created_at
                FROM data_query_results
                WHERE owner_actor_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new ResultSummary(
                rs.getLong("id"), rs.getString("candidate_id"), rs.getInt("row_count"),
                rs.getBoolean("truncated"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()),
                actorId, size, page * size);
    }

    /** 返回当前操作者创建的 Report 交接及其状态。 */
    public List<HandoffSummary> listHandoffs(int page, int size) {
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.query("""
                SELECT handoff.id, handoff.query_result_id, handoff.title,
                       handoff.status, handoff.source_reference, result.row_count,
                       result.expires_at, handoff.consumed_at, handoff.created_at
                FROM data_report_handoffs handoff
                JOIN data_query_results result ON result.id = handoff.query_result_id
                WHERE handoff.owner_actor_id = ?
                ORDER BY handoff.created_at DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new HandoffSummary(
                rs.getLong("id"), rs.getLong("query_result_id"), rs.getString("title"),
                rs.getString("status"), rs.getString("source_reference"),
                rs.getInt("row_count"), rs.getTimestamp("expires_at").toInstant(),
                instant(rs.getTimestamp("consumed_at")), rs.getTimestamp("created_at").toInstant()),
                actorId, size, page * size);
    }

    public StoredResult requireOwned(long resultId) {
        String actorId = actorProvider.currentActor().actorId();
        List<StoredResult> rows = jdbcTemplate.query("""
                SELECT id, columns_json::text, rows_json::text, row_count, truncated, created_at
                FROM data_query_results
                WHERE id = ? AND owner_actor_id = ? AND expires_at > now()
                """, (rs, rowNum) -> {
            try {
                return new StoredResult(
                        rs.getLong("id"),
                        objectMapper.readValue(rs.getString("columns_json"),
                                new TypeReference<List<QueryColumn>>() { }),
                        objectMapper.readValue(rs.getString("rows_json"),
                                new TypeReference<List<Map<String, Object>>>() { }),
                        rs.getInt("row_count"),
                        rs.getBoolean("truncated"),
                        rs.getTimestamp("created_at").toInstant());
            } catch (JacksonException ex) {
                throw new IllegalStateException("读取查询结果快照失败", ex);
            }
        }, resultId, actorId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("查询结果序列化失败", ex);
        }
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (safe.startsWith("=") || safe.startsWith("+") || safe.startsWith("-") || safe.startsWith("@")) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public record StoredResult(long id, List<QueryColumn> columns, List<Map<String, Object>> rows,
                               int rowCount, boolean truncated, Instant createdAt) { }
    public record ResultSummary(long id, String candidateId, int rowCount, boolean truncated,
                                Instant expiresAt, Instant createdAt) { }
    public record HandoffSummary(long id, long resultId, String title, String status,
                                 String sourceReference, int rowCount, Instant resultExpiresAt,
                                 Instant consumedAt, Instant createdAt) { }
    public record Handoff(Long id, long resultId, String sourceReference, String status) { }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
