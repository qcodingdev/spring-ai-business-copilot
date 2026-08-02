package dev.qcoding.businesscopilot.supportcopilot.integration;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketSentiment;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketUrgency;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicket;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 外部工单只读导入、SLA、相似工单、质量统计和确认后草稿回写。 */
public class SupportEnterpriseService {

    private final JdbcTemplate jdbcTemplate;
    private final SupportTicketRepository ticketRepository;
    private final List<SupportExternalAdapter> adapters;
    private final CurrentActorProvider actorProvider;
    private final ConfirmationTokenService tokenService;
    private final ExternalSecretResolver secretResolver;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final ObjectMapper objectMapper;
    private final ExternalEndpointPolicy endpointPolicy;

    public SupportEnterpriseService(
            JdbcTemplate jdbcTemplate,
            SupportTicketRepository ticketRepository,
            List<SupportExternalAdapter> adapters,
            CurrentActorProvider actorProvider,
            ConfirmationTokenService tokenService,
            ExternalSecretResolver secretResolver,
            SensitiveTextMasker sensitiveTextMasker,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.ticketRepository = ticketRepository;
        this.adapters = List.copyOf(adapters);
        this.actorProvider = actorProvider;
        this.tokenService = tokenService;
        this.secretResolver = secretResolver;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
    }

    public SupportExternalConnection save(ConnectionCommand command) {
        ExternalSecretResolver.validateRef(command.secretRef());
        endpointPolicy.validateBaseUrl(command.baseUrl());
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.queryForObject("""
                INSERT INTO support_external_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (connection_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    secret_ref = EXCLUDED.secret_ref,
                    enabled = EXCLUDED.enabled,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    updated_at = now()
                RETURNING id, connection_key, display_name, provider, base_url,
                          secret_ref, enabled, owner_actor_id
                """, this::mapConnection, command.connectionKey().trim(),
                command.displayName().trim(), command.provider().name(),
                command.baseUrl().trim(), command.secretRef().trim(),
                command.enabled(), actorId);
    }

    public List<SupportExternalConnection> connections() {
        return jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider, base_url,
                       secret_ref, enabled, owner_actor_id
                FROM support_external_connections
                ORDER BY display_name
                """, this::mapConnection);
    }

    public ImportResult importRecent(long connectionId, int limit) {
        SupportExternalConnection connection = requireConnection(connectionId);
        if (!connection.enabled()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "外部工单连接尚未启用");
        }
        SupportExternalAdapter adapter = adapter(connection.provider());
        List<SupportExternalAdapter.ExternalTicket> externalTickets =
                adapter.fetchRecent(connection, Math.max(1, Math.min(limit, 100)));
        int created = 0;
        int updated = 0;
        for (SupportExternalAdapter.ExternalTicket external : externalTickets) {
            List<Long> existing = jdbcTemplate.query("""
                    SELECT id FROM support_tickets
                    WHERE external_connection_id = ? AND external_id = ?
                    """, (rs, rowNum) -> rs.getLong(1), connectionId, external.externalId());
            Long ticketId;
            if (existing.isEmpty()) {
                TicketUrgency urgency = slaStatus(external.slaDueAt()) == SlaStatus.AT_RISK
                        ? TicketUrgency.HIGH : TicketUrgency.MEDIUM;
                SupportTicket ticket = ticketRepository.save(new SupportTicket(
                        null, external.externalId(),
                        sensitiveTextMasker.mask(external.customerMessage()), external.channel(),
                        TicketCategory.OTHER, TicketSentiment.NEUTRAL, urgency,
                        SupportTicketStatus.RECEIVED, actorProvider.currentActor().actorId(),
                        null, null));
                ticketId = ticket.id();
                created++;
            } else {
                ticketId = existing.getFirst();
                updated++;
            }
            jdbcTemplate.update("""
                    UPDATE support_tickets
                    SET external_connection_id = ?, external_updated_at = ?,
                        sla_due_at = ?, sla_status = ?, updated_at = now()
                    WHERE id = ?
                    """, connectionId, timestamp(external.updatedAt()),
                    timestamp(external.slaDueAt()), slaStatus(external.slaDueAt()).name(), ticketId);
            saveContext(ticketId, "CUSTOMER", external.externalId(),
                    external.customerContext(), external.updatedAt());
            saveContext(ticketId, "ORDER", external.externalId(),
                    external.orderContext(), external.updatedAt());
            saveContext(ticketId, "SERVICE_STATUS", external.externalId(),
                    external.serviceContext(), external.updatedAt());
        }
        return new ImportResult(externalTickets.size(), created, updated);
    }

    public List<SimilarTicket> similar(long ticketId, int limit) {
        return jdbcTemplate.query("""
                WITH target AS (
                    SELECT customer_message FROM support_tickets WHERE id = ?
                )
                SELECT candidate.id, candidate.external_id, candidate.customer_message,
                       candidate.status, candidate.category,
                       ts_rank(
                           to_tsvector('simple', candidate.customer_message),
                           plainto_tsquery('simple', target.customer_message)
                       ) AS similarity
                FROM support_tickets candidate, target
                WHERE candidate.id <> ?
                  AND to_tsvector('simple', candidate.customer_message)
                      @@ plainto_tsquery('simple', target.customer_message)
                ORDER BY similarity DESC, candidate.created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new SimilarTicket(
                rs.getLong("id"), rs.getString("external_id"),
                rs.getString("customer_message"), rs.getString("status"),
                rs.getString("category"), rs.getDouble("similarity")),
                ticketId, ticketId, Math.max(1, Math.min(limit, 20)));
    }

    public int refreshSla() {
        return jdbcTemplate.update("""
                UPDATE support_tickets
                SET sla_status = CASE
                    WHEN sla_due_at IS NULL THEN 'NOT_CONFIGURED'
                    WHEN status IN ('CLOSED', 'CANCELED') THEN 'PAUSED'
                    WHEN sla_due_at <= now() THEN 'BREACHED'
                    WHEN sla_due_at <= now() + interval '2 hours' THEN 'AT_RISK'
                    ELSE 'ON_TRACK'
                END,
                updated_at = now()
                WHERE status NOT IN ('CLOSED', 'CANCELED')
                   OR sla_status <> 'PAUSED'
                """);
    }

    public QualityMetrics metrics() {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) FILTER (WHERE d.decision_outcome = 'ACCEPTED') AS accepted,
                    COUNT(*) FILTER (WHERE d.decision_outcome = 'EDITED_ACCEPTED') AS edited,
                    COUNT(*) FILTER (WHERE d.decision_outcome = 'REJECTED') AS rejected,
                    COUNT(*) FILTER (WHERE d.review_queue = TRUE) AS handed_off,
                    COUNT(*) FILTER (WHERE t.sla_status = 'AT_RISK') AS sla_at_risk,
                    COUNT(*) FILTER (WHERE t.sla_status = 'BREACHED') AS sla_breached
                FROM support_tickets t
                LEFT JOIN support_reply_drafts d ON d.ticket_id = t.id
                """, (rs, rowNum) -> new QualityMetrics(
                rs.getLong("accepted"), rs.getLong("edited"), rs.getLong("rejected"),
                rs.getLong("handed_off"), rs.getLong("sla_at_risk"),
                rs.getLong("sla_breached")));
    }

    public WritebackIntent prepareWriteback(long draftId) {
        List<WritebackSource> sources = jdbcTemplate.query("""
                SELECT d.id, COALESCE(d.edited_draft_text, d.original_draft_text) AS draft_text,
                       t.external_id, t.external_connection_id
                FROM support_reply_drafts d
                JOIN support_tickets t ON t.id = d.ticket_id
                WHERE d.id = ? AND d.status = 'CONFIRMED'
                  AND t.external_id IS NOT NULL AND t.external_connection_id IS NOT NULL
                """, (rs, rowNum) -> new WritebackSource(
                rs.getLong("id"), rs.getString("draft_text"),
                rs.getString("external_id"), rs.getLong("external_connection_id")), draftId);
        if (sources.isEmpty()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "只有已确认且来自外部系统的草稿可以准备回写");
        }
        WritebackSource source = sources.getFirst();
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        String actorId = actorProvider.currentActor().actorId();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO support_draft_writebacks (
                    draft_id, connection_id, external_ticket_id, payload_hash,
                    status, token_digest, expires_at, requested_by
                ) VALUES (?, ?, ?, ?, 'PENDING_CONFIRMATION', ?, ?, ?)
                ON CONFLICT (draft_id, connection_id, external_ticket_id) DO UPDATE SET
                    payload_hash = EXCLUDED.payload_hash,
                    status = 'PENDING_CONFIRMATION',
                    token_digest = EXCLUDED.token_digest,
                    expires_at = EXCLUDED.expires_at,
                    requested_by = EXCLUDED.requested_by,
                    confirmed_by = NULL,
                    completed_at = NULL,
                    error_category = NULL,
                    updated_at = now()
                RETURNING id
                """, Long.class, draftId, source.connectionId(), source.externalTicketId(),
                sha256(source.draftText()), token.digest(), Timestamp.from(expiresAt), actorId);
        return new WritebackIntent(id, token.rawToken(), expiresAt);
    }

    public WritebackResult confirmWriteback(long writebackId, String rawToken) {
        List<PendingWriteback> pending = jdbcTemplate.query("""
                SELECT w.id, w.draft_id, w.connection_id, w.external_ticket_id,
                       w.token_digest, w.expires_at,
                       COALESCE(d.edited_draft_text, d.original_draft_text) AS draft_text
                FROM support_draft_writebacks w
                JOIN support_reply_drafts d ON d.id = w.draft_id
                WHERE w.id = ? AND w.status = 'PENDING_CONFIRMATION'
                """, (rs, rowNum) -> new PendingWriteback(
                rs.getLong("id"), rs.getLong("draft_id"), rs.getLong("connection_id"),
                rs.getString("external_ticket_id"), rs.getString("token_digest"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("draft_text")),
                writebackId);
        if (pending.isEmpty()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        PendingWriteback writeback = pending.getFirst();
        if (!tokenService.matches(rawToken, writeback.tokenDigest())
                || !writeback.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String actorId = actorProvider.currentActor().actorId();
        int claimed = jdbcTemplate.update("""
                UPDATE support_draft_writebacks
                SET status = 'CONFIRMED', token_digest = NULL, confirmed_by = ?, updated_at = now()
                WHERE id = ? AND status = 'PENDING_CONFIRMATION' AND expires_at > now()
                """, actorId, writebackId);
        if (claimed != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        SupportExternalConnection connection = requireConnection(writeback.connectionId());
        try {
            adapter(connection.provider()).writeConfirmedDraft(
                    connection, writeback.externalTicketId(), writeback.draftText());
            jdbcTemplate.update("""
                    UPDATE support_draft_writebacks
                    SET status = 'COMPLETED', completed_at = now(), updated_at = now()
                    WHERE id = ? AND status = 'CONFIRMED'
                    """, writebackId);
            return new WritebackResult(writebackId, "COMPLETED");
        } catch (RuntimeException ex) {
            jdbcTemplate.update("""
                    UPDATE support_draft_writebacks
                    SET status = 'FAILED', error_category = 'EXTERNAL_WRITE_FAILED',
                        updated_at = now()
                    WHERE id = ?
                    """, writebackId);
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "外部客服系统回写失败，未自动重试");
        }
    }

    private void saveContext(long ticketId, String type, String reference,
                             Map<String, Object> context, Instant observedAt) {
        if (context == null || context.isEmpty()) return;
        String sanitized = sensitiveTextMasker.mask(json(context));
        jdbcTemplate.update("""
                INSERT INTO support_ticket_context_snapshots (
                    ticket_id, context_type, source_reference, sanitized_payload,
                    observed_at, expires_at
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, ticketId, type, reference, sanitized,
                timestamp(observedAt), Timestamp.from(Instant.now().plus(Duration.ofHours(24))));
    }

    private SupportExternalAdapter adapter(SupportExternalProvider provider) {
        return adapters.stream().filter(candidate -> candidate.supports(provider)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STATE_CONFLICT, "外部客服适配器尚未装配"));
    }

    private SupportExternalConnection requireConnection(long id) {
        List<SupportExternalConnection> rows = jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider, base_url,
                       secret_ref, enabled, owner_actor_id
                FROM support_external_connections WHERE id = ?
                """, this::mapConnection, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private SupportExternalConnection mapConnection(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SupportExternalConnection(
                rs.getLong("id"), rs.getString("connection_key"), rs.getString("display_name"),
                SupportExternalProvider.valueOf(rs.getString("provider")),
                rs.getString("base_url"), rs.getString("secret_ref"),
                rs.getBoolean("enabled"), rs.getString("owner_actor_id"));
    }

    private SlaStatus slaStatus(Instant dueAt) {
        if (dueAt == null) return SlaStatus.NOT_CONFIGURED;
        if (!dueAt.isAfter(Instant.now())) return SlaStatus.BREACHED;
        if (!dueAt.isAfter(Instant.now().plus(Duration.ofHours(2)))) return SlaStatus.AT_RISK;
        return SlaStatus.ON_TRACK;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("客服上下文序列化失败", ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? Timestamp.from(Instant.now()) : Timestamp.from(value);
    }

    public enum SlaStatus { NOT_CONFIGURED, ON_TRACK, AT_RISK, BREACHED, PAUSED }
    private record WritebackSource(long draftId, String draftText,
                                   String externalTicketId, long connectionId) { }
    private record PendingWriteback(long id, long draftId, long connectionId,
                                    String externalTicketId, String tokenDigest,
                                    Instant expiresAt, String draftText) { }
    public record ConnectionCommand(String connectionKey, String displayName,
                                    SupportExternalProvider provider, String baseUrl,
                                    String secretRef, boolean enabled) { }
    public record ImportResult(int fetched, int created, int updated) { }
    public record SimilarTicket(long ticketId, String externalId, String customerMessage,
                                String status, String category, double similarity) { }
    public record QualityMetrics(long accepted, long editedAccepted, long rejected,
                                 long handedOff, long slaAtRisk, long slaBreached) { }
    public record WritebackIntent(Long id, String confirmationToken, Instant expiresAt) { }
    public record WritebackResult(long id, String status) { }
}
