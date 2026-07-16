package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
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
import java.util.stream.Collectors;

/** JDBC implementation for the small, transactional Report Copilot draft lifecycle. */
public class JdbcReportDraftRepository implements ReportDraftRepository {

    private static final LlmReportOutput EMPTY_REVIEW_CONTENT = new LlmReportOutput(null, List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of());

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider actorProvider;
    private final ConfirmationTokenService tokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcReportDraftRepository(JdbcTemplate jdbcTemplate,
                                     CurrentActorProvider actorProvider,
                                     ConfirmationTokenService tokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
        this.tokenService = tokenService;
    }

    @Override
    public ReportDraft save(ReportRequestPreparationService.ReportRequestPreview preview, LlmReportOutput content,
                            String modelName, Duration draftTtl) {
        return saveDraft(preview, content, ReportDraftStatus.DRAFTED, null, draftTtl);
    }

    @Override
    public ReportDraft saveNeedsReview(ReportRequestPreparationService.ReportRequestPreview preview,
                                       List<String> reviewReasons, String modelName, Duration draftTtl) {
        return saveDraft(preview, EMPTY_REVIEW_CONTENT, ReportDraftStatus.NEEDS_REVIEW,
                joinReviewReasons(reviewReasons), draftTtl);
    }

    private ReportDraft saveDraft(ReportRequestPreparationService.ReportRequestPreview preview, LlmReportOutput content,
                                  ReportDraftStatus status, String reviewReasons, Duration draftTtl) {
        Instant now = Instant.now();
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String ownerActorId = actor.actorId();
        long requestId = insertRequest(preview, ownerActorId, now);
        insertSources(requestId, preview.sources(), now);
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        Instant expiresAt = now.plus(draftTtl);
        String contentJson = serialize(content);
        String citedSourceIds = content.citations().stream().map(citation -> citation.sourceId())
                .distinct().collect(Collectors.joining(","));

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_drafts (request_id, structured_content, cited_source_ids, status, review_reasons, confirmation_token_digest, owner_actor_id, expires_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, requestId);
            statement.setString(2, contentJson);
            statement.setString(3, citedSourceIds);
            statement.setString(4, status.name());
            statement.setString(5, reviewReasons);
            statement.setString(6, token.digest());
            statement.setString(7, ownerActorId);
            statement.setTimestamp(8, Timestamp.from(expiresAt));
            statement.setTimestamp(9, Timestamp.from(now));
            statement.setTimestamp(10, Timestamp.from(now));
            return statement;
        }, keyHolder);
        return new ReportDraft(keyHolder.getKey().longValue(), requestId, content, status,
                reviewReasons, token.rawToken(), token.digest(), ownerActorId,
                null, expiresAt, now, now);
    }

    @Override
    public Optional<ReportDraft> findById(Long draftId) {
        List<ReportDraft> drafts = jdbcTemplate.query(selectDraftSql() + " WHERE id = ?", this::mapDraft, draftId);
        return drafts.isEmpty() ? Optional.empty() : Optional.of(drafts.getFirst());
    }

    @Override
    public boolean transitionStatus(Long draftId, ReportDraftStatus expected, ReportDraftStatus target,
                                    String actionActorId) {
        int rows = jdbcTemplate.update("UPDATE report_drafts SET status = ?, confirmation_token_digest = NULL, action_actor_id = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND expires_at > ?",
                target.name(), actionActorId, Timestamp.from(Instant.now()), draftId,
                expected.name(), Timestamp.from(Instant.now()));
        return rows == 1;
    }

    private long insertRequest(ReportRequestPreparationService.ReportRequestPreview preview,
                               String ownerActorId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_requests (report_type, period_start, period_end, title, owner_actor_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, preview.reportType().name());
            statement.setObject(2, preview.period().periodStart());
            statement.setObject(3, preview.period().periodEnd());
            statement.setString(4, preview.title());
            statement.setString(5, ownerActorId);
            statement.setTimestamp(6, Timestamp.from(now));
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

    private String joinReviewReasons(List<String> reviewReasons) {
        if (reviewReasons == null || reviewReasons.isEmpty()) {
            return "Report output requires manual evidence review.";
        }
        return reviewReasons.stream().filter(reason -> reason != null && !reason.isBlank()).limit(20)
                .collect(Collectors.joining("\n"));
    }

    private ReportDraft mapDraft(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ReportDraft(rs.getLong("id"), rs.getLong("request_id"), deserialize(rs.getString("structured_content")),
                ReportDraftStatus.valueOf(rs.getString("status")), rs.getString("review_reasons"),
                null, rs.getString("confirmation_token_digest"), rs.getString("owner_actor_id"),
                rs.getString("action_actor_id"), toInstant(rs.getTimestamp("expires_at")),
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
        return "SELECT id, request_id, structured_content, status, review_reasons, confirmation_token_digest, owner_actor_id, action_actor_id, expires_at, created_at, updated_at FROM report_drafts";
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
