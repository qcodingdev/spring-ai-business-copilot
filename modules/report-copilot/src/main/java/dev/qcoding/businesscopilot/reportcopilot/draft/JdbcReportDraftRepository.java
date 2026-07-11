package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** JDBC implementation for the small, transactional Report Copilot draft lifecycle. */
public class JdbcReportDraftRepository implements ReportDraftRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcReportDraftRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ReportDraft save(ReportRequestPreparationService.ReportRequestPreview preview, LlmReportOutput content,
                            String modelName, Duration draftTtl) {
        Instant now = Instant.now();
        long requestId = insertRequest(preview, now);
        insertSources(requestId, preview.sources(), now);
        String token = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(draftTtl);
        String contentJson = serialize(content);
        String citedSourceIds = content.citations().stream().map(citation -> citation.sourceId())
                .distinct().collect(Collectors.joining(","));

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_drafts (request_id, structured_content, cited_source_ids, status, review_reasons, confirmation_token, expires_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, requestId);
            statement.setString(2, contentJson);
            statement.setString(3, citedSourceIds);
            statement.setString(4, ReportDraftStatus.DRAFTED.name());
            statement.setString(5, null);
            statement.setString(6, token);
            statement.setTimestamp(7, Timestamp.from(expiresAt));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setTimestamp(9, Timestamp.from(now));
            return statement;
        }, keyHolder);
        return new ReportDraft(keyHolder.getKey().longValue(), requestId, content, ReportDraftStatus.DRAFTED,
                null, token, expiresAt, now, now);
    }

    @Override
    public Optional<ReportDraft> findByConfirmationToken(String confirmationToken) {
        List<ReportDraft> drafts = jdbcTemplate.query(selectDraftSql() + " WHERE confirmation_token = ?",
                this::mapDraft, confirmationToken);
        return drafts.isEmpty() ? Optional.empty() : Optional.of(drafts.getFirst());
    }

    @Override
    public Optional<ReportDraft> findById(Long draftId) {
        List<ReportDraft> drafts = jdbcTemplate.query(selectDraftSql() + " WHERE id = ?", this::mapDraft, draftId);
        return drafts.isEmpty() ? Optional.empty() : Optional.of(drafts.getFirst());
    }

    @Override
    public boolean transitionStatus(Long draftId, ReportDraftStatus expected, ReportDraftStatus target) {
        int rows = jdbcTemplate.update("UPDATE report_drafts SET status = ?, confirmation_token = NULL, updated_at = ? "
                        + "WHERE id = ? AND status = ?", target.name(), Timestamp.from(Instant.now()), draftId, expected.name());
        return rows == 1;
    }

    private long insertRequest(ReportRequestPreparationService.ReportRequestPreview preview, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_requests (report_type, period_start, period_end, title, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, preview.reportType().name());
            statement.setObject(2, preview.period().periodStart());
            statement.setObject(3, preview.period().periodEnd());
            statement.setString(4, preview.title());
            statement.setTimestamp(5, Timestamp.from(now));
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertSources(long requestId, List<ReportSource> sources, Instant now) {
        for (ReportSource source : sources) {
            jdbcTemplate.update("INSERT INTO report_sources (request_id, source_type, source_ref, source_title, sanitized_content, source_hash, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", requestId, source.sourceType().name(), source.sourceId(),
                    source.title(), source.sanitizedContent(), source.sourceHash(), Timestamp.from(now));
        }
    }

    private String serialize(LlmReportOutput content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to serialize report draft content", ex);
        }
    }

    private ReportDraft mapDraft(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ReportDraft(rs.getLong("id"), rs.getLong("request_id"), deserialize(rs.getString("structured_content")),
                ReportDraftStatus.valueOf(rs.getString("status")), rs.getString("review_reasons"),
                rs.getString("confirmation_token"), toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("updated_at")));
    }

    private LlmReportOutput deserialize(String contentJson) {
        try {
            return objectMapper.readValue(contentJson, LlmReportOutput.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to deserialize report draft content", ex);
        }
    }

    private String selectDraftSql() {
        return "SELECT id, request_id, structured_content, status, review_reasons, confirmation_token, expires_at, created_at, updated_at FROM report_drafts";
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
