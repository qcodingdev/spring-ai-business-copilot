package dev.qcoding.businesscopilot.supportcopilot.integration;

import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAction;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
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
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;

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
    private final ObjectAccessPolicy accessPolicy;
    private final SupportAuditService auditService;

    public SupportEnterpriseService(
            JdbcTemplate jdbcTemplate,
            SupportTicketRepository ticketRepository,
            List<SupportExternalAdapter> adapters,
            CurrentActorProvider actorProvider,
            ConfirmationTokenService tokenService,
            ExternalSecretResolver secretResolver,
            SensitiveTextMasker sensitiveTextMasker,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy,
            ObjectAccessPolicy accessPolicy,
            SupportAuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.ticketRepository = ticketRepository;
        this.adapters = List.copyOf(adapters);
        this.actorProvider = actorProvider;
        this.tokenService = tokenService;
        this.secretResolver = secretResolver;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
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
            if (external.externalId() == null || external.externalId().isBlank()
                    || external.customerMessage() == null || external.customerMessage().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "外部工单缺少稳定编号或客户消息");
            }
            String actorId = actorProvider.currentActor().actorId();
            TicketUrgency urgency = slaUrgency(external.slaDueAt());
            String maskedMessage = sensitiveTextMasker.mask(external.customerMessage());
            List<Long> inserted = jdbcTemplate.query("""
                    INSERT INTO support_tickets (
                        external_id, customer_message, channel, category, sentiment, urgency,
                        status, owner_actor_id, system_managed, external_connection_id,
                        external_updated_at, sla_due_at, sla_status, created_at, updated_at
                    ) VALUES (?, ?, ?, 'OTHER', 'NEUTRAL', ?, 'RECEIVED', ?, TRUE, ?, ?, ?, ?, now(), now())
                    ON CONFLICT (external_connection_id, external_id)
                        WHERE external_connection_id IS NOT NULL AND external_id IS NOT NULL
                    DO NOTHING
                    RETURNING id
                    """, (rs, rowNum) -> rs.getLong(1),
                    external.externalId().trim(), maskedMessage, safeChannel(external.channel()),
                    urgency.name(), actorId, connectionId, timestamp(external.updatedAt()),
                    nullableTimestamp(external.slaDueAt()), slaStatus(external.slaDueAt()).name());
            Long ticketId;
            String eventType;
            if (!inserted.isEmpty()) {
                ticketId = inserted.getFirst();
                created++;
                eventType = "EXTERNAL_IMPORTED";
            } else {
                ticketId = jdbcTemplate.queryForObject("""
                        UPDATE support_tickets
                        SET customer_message = ?, channel = ?, system_managed = TRUE,
                            external_updated_at = ?, sla_due_at = ?, sla_status = ?,
                            urgency = CASE WHEN status IN ('RECEIVED', 'FAILED') THEN ? ELSE urgency END,
                            updated_at = now()
                        WHERE external_connection_id = ? AND external_id = ?
                        RETURNING id
                        """, Long.class, maskedMessage, safeChannel(external.channel()),
                        timestamp(external.updatedAt()), nullableTimestamp(external.slaDueAt()),
                        slaStatus(external.slaDueAt()).name(), urgency.name(), connectionId,
                        external.externalId().trim());
                updated++;
                eventType = "EXTERNAL_REFRESHED";
            }
            saveContext(ticketId, "CUSTOMER", external.externalId(),
                    external.customerContext(), external.updatedAt());
            saveContext(ticketId, "ORDER", external.externalId(),
                    external.orderContext(), external.updatedAt());
            saveContext(ticketId, "SERVICE_STATUS", external.externalId(),
                    external.serviceContext(), external.updatedAt());
            recordAudit(ticketId, eventType, actorId, actorId, null);
        }
        return new ImportResult(externalTickets.size(), created, updated);
    }

    public List<SimilarTicket> similar(long ticketId, int limit) {
        CurrentActor actor = actorProvider.currentActor();
        return jdbcTemplate.query("""
                WITH target AS (
                    SELECT customer_message FROM support_tickets
                    WHERE id = ? AND (? OR system_managed = TRUE OR owner_actor_id = ?)
                )
                SELECT candidate.id, candidate.external_id, candidate.customer_message,
                       candidate.status, candidate.category,
                       ts_rank(
                           to_tsvector('simple', candidate.customer_message),
                           plainto_tsquery('simple', target.customer_message)
                       ) AS similarity
                FROM support_tickets candidate, target
                WHERE candidate.id <> ?
                  AND (? OR candidate.system_managed = TRUE OR candidate.owner_actor_id = ?)
                  AND to_tsvector('simple', candidate.customer_message)
                      @@ plainto_tsquery('simple', target.customer_message)
                ORDER BY similarity DESC, candidate.created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new SimilarTicket(
                rs.getLong("id"), rs.getString("external_id"),
                rs.getString("customer_message"), rs.getString("status"),
                rs.getString("category"), rs.getDouble("similarity")),
                ticketId, actor.hasRole(BusinessRole.ADMIN), actor.actorId(), ticketId,
                actor.hasRole(BusinessRole.ADMIN), actor.actorId(),
                Math.max(1, Math.min(limit, 20)));
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

    /** Whether a confirmed draft can enter the external writeback flow. */
    public WritebackCapability writebackCapability(long draftId) {
        WritebackSource source = requireWritebackSource(draftId);
        requireWritebackAccess(source);
        return new WritebackCapability("CONFIRMED".equals(source.status())
                && source.externalTicketId() != null && source.connectionId() != null);
    }

    public WritebackIntent prepareWriteback(long draftId) {
        WritebackSource source = requireWritebackSource(draftId);
        requireWritebackAccess(source);
        if (!"CONFIRMED".equals(source.status()) || source.externalTicketId() == null
                || source.connectionId() == null) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "只有已确认且来自外部系统的草稿可以准备回写");
        }
        List<String> existing = jdbcTemplate.query("""
                SELECT status FROM support_draft_writebacks
                WHERE draft_id = ? AND connection_id = ? AND external_ticket_id = ?
                """, (rs, rowNum) -> rs.getString(1), draftId,
                source.connectionId(), source.externalTicketId());
        if (!existing.isEmpty() && !"FAILED".equals(existing.getFirst())
                && !"CANCELED".equals(existing.getFirst()) && !"EXPIRED".equals(existing.getFirst())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该草稿已有进行中、结果未知或已完成的回写记录");
        }
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
                    confirmed_by = NULL, attempt_count = 0, last_attempt_at = NULL,
                    completed_at = NULL,
                    error_category = NULL,
                    updated_at = now()
                RETURNING id
                """, Long.class, draftId, source.connectionId(), source.externalTicketId(),
                sha256(source.draftText()), token.digest(), Timestamp.from(expiresAt), actorId);
        recordAudit(source.ticketId(), "WRITEBACK_PREPARED", source.ownerActorId(), actorId, null);
        return new WritebackIntent(id, "PENDING_CONFIRMATION", token.rawToken(), expiresAt);
    }

    public WritebackResult confirmWriteback(long writebackId, String rawToken) {
        List<PendingWriteback> pending = jdbcTemplate.query("""
                SELECT w.id, w.draft_id, w.connection_id, w.external_ticket_id,
                       w.token_digest, w.expires_at, w.payload_hash, w.requested_by,
                       COALESCE(d.edited_draft_text, d.original_draft_text) AS draft_text,
                       d.ticket_id, d.owner_actor_id, d.reviewer_actor_id, d.review_queue
                FROM support_draft_writebacks w
                JOIN support_reply_drafts d ON d.id = w.draft_id
                WHERE w.id = ? AND w.status = 'PENDING_CONFIRMATION'
                """, (rs, rowNum) -> new PendingWriteback(
                rs.getLong("id"), rs.getLong("draft_id"), rs.getLong("connection_id"),
                rs.getString("external_ticket_id"), rs.getString("token_digest"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("payload_hash"),
                rs.getString("requested_by"), rs.getString("draft_text"),
                rs.getLong("ticket_id"),
                rs.getString("owner_actor_id"), rs.getString("reviewer_actor_id"),
                rs.getBoolean("review_queue")),
                writebackId);
        if (pending.isEmpty()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        PendingWriteback writeback = pending.getFirst();
        if (!tokenService.matches(rawToken, writeback.tokenDigest())
                || !writeback.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        String actorId = actorProvider.currentActor().actorId();
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, ObjectAction.CONFIRM, writeback.ownerActorId(),
                writeback.reviewerActorId(), false)
                || (!actor.hasRole(BusinessRole.ADMIN)
                    && !actorId.equals(writeback.requestedBy()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!sha256(writeback.draftText()).equals(writeback.payloadHash())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "草稿内容已变化，请重新准备回写");
        }
        SupportExternalConnection connection = requireConnection(writeback.connectionId());
        if (!connection.enabled()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "外部客服连接未启用");
        }
        SupportExternalAdapter externalAdapter = adapter(connection.provider());
        int claimed = jdbcTemplate.update("""
                UPDATE support_draft_writebacks
                SET status = 'PROCESSING', token_digest = NULL, confirmed_by = ?,
                    attempt_count = attempt_count + 1, last_attempt_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PENDING_CONFIRMATION' AND expires_at > now()
                """, actorId, writebackId);
        if (claimed != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        recordAudit(writeback.ticketId(), "WRITEBACK_PROCESSING",
                writeback.ownerActorId(), actorId, null);
        String idempotencyKey = "support-writeback-" + writebackId;
        try {
            externalAdapter.writeConfirmedDraft(
                    connection, writeback.externalTicketId(), writeback.draftText(), idempotencyKey);
            jdbcTemplate.update("""
                    UPDATE support_draft_writebacks
                    SET status = 'COMPLETED', completed_at = now(), external_receipt = ?,
                        updated_at = now()
                    WHERE id = ? AND status = 'PROCESSING'
                    """, idempotencyKey, writebackId);
            auditService.record(newAudit(writeback.ticketId(), "WRITEBACK_COMPLETED",
                    writeback.ownerActorId(), actorId, null));
            return new WritebackResult(writebackId, "COMPLETED");
        } catch (RuntimeException ex) {
            jdbcTemplate.update("""
                    UPDATE support_draft_writebacks
                    SET status = 'UNKNOWN', error_category = 'EXTERNAL_OUTCOME_UNKNOWN',
                        updated_at = now()
                    WHERE id = ?
                    """, writebackId);
            auditService.record(newAudit(writeback.ticketId(), "WRITEBACK_UNKNOWN",
                    writeback.ownerActorId(), actorId, "EXTERNAL_OUTCOME_UNKNOWN"));
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "外部客服系统结果未知，系统不会自动重试；请先在外部系统核对");
        }
    }

    public WritebackStatus writebackStatus(long writebackId) {
        List<WritebackStatus> rows = jdbcTemplate.query("""
                SELECT w.id, w.draft_id, w.status, w.attempt_count, w.last_attempt_at,
                       w.external_receipt, w.error_category, w.requested_by,
                       d.owner_actor_id, d.reviewer_actor_id, d.review_queue
                FROM support_draft_writebacks w
                JOIN support_reply_drafts d ON d.id = w.draft_id
                WHERE w.id = ?
                """, (rs, rowNum) -> {
            requireWritebackAccess(new WritebackSource(
                    rs.getLong("draft_id"), 0L, null, null, null, null,
                    rs.getString("owner_actor_id"), rs.getString("reviewer_actor_id"),
                    rs.getBoolean("review_queue")));
            return new WritebackStatus(rs.getLong("id"), rs.getLong("draft_id"),
                    rs.getString("status"), rs.getInt("attempt_count"),
                    rs.getTimestamp("last_attempt_at") == null ? null
                            : rs.getTimestamp("last_attempt_at").toInstant(),
                    rs.getString("external_receipt"), rs.getString("error_category"));
        }, writebackId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    /** Admin records externally verified resolution; retry requires explicit no-write evidence. */
    public WritebackIntent resolveUnknown(long writebackId, ResolutionCommand command) {
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.hasRole(BusinessRole.ADMIN)) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (command.evidenceReference() == null || command.evidenceReference().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "解决结果未知状态必须提供外部核对证据引用");
        }
        if (command.resolution() == WritebackResolution.COMPLETED) {
            int updated = jdbcTemplate.update("""
                    UPDATE support_draft_writebacks
                    SET status = 'COMPLETED', completed_at = now(), external_receipt = ?,
                        error_category = NULL, updated_at = now()
                    WHERE id = ? AND status = 'UNKNOWN'
                    """, command.evidenceReference().trim(), writebackId);
            if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            return new WritebackIntent(writebackId, "COMPLETED", null, null);
        }
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        int updated = jdbcTemplate.update("""
                UPDATE support_draft_writebacks
                SET status = 'PENDING_CONFIRMATION', token_digest = ?, expires_at = ?,
                    error_category = NULL, external_receipt = ?, requested_by = ?, updated_at = now()
                WHERE id = ? AND status = 'UNKNOWN'
                """, token.digest(), Timestamp.from(expiresAt),
                "SAFE_TO_RETRY:" + command.evidenceReference().trim(), actor.actorId(), writebackId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return new WritebackIntent(writebackId, "PENDING_CONFIRMATION", token.rawToken(), expiresAt);
    }

    @Scheduled(fixedDelayString = "${business-copilot.support-copilot.writeback-reconcile-delay:PT1M}")
    public void reconcileWritebacksAndContexts() {
        jdbcTemplate.update("""
                UPDATE support_draft_writebacks
                SET status = 'UNKNOWN', error_category = 'PROCESS_INTERRUPTED', updated_at = now()
                WHERE status = 'PROCESSING' AND last_attempt_at < now() - interval '5 minutes'
                """);
        jdbcTemplate.update("""
                UPDATE support_draft_writebacks
                SET status = 'EXPIRED', token_digest = NULL, updated_at = now()
                WHERE status = 'PENDING_CONFIRMATION' AND expires_at <= now()
                """);
        jdbcTemplate.update("DELETE FROM support_ticket_context_snapshots WHERE expires_at <= now()");
    }

    private WritebackSource requireWritebackSource(long draftId) {
        List<WritebackSource> rows = jdbcTemplate.query("""
                SELECT d.id AS draft_id,
                       COALESCE(d.edited_draft_text, d.original_draft_text) AS draft_text,
                       t.external_id AS external_ticket_id,
                       t.external_connection_id AS connection_id,
                       d.ticket_id, d.status, d.owner_actor_id, d.reviewer_actor_id, d.review_queue
                FROM support_reply_drafts d
                JOIN support_tickets t ON t.id = d.ticket_id
                WHERE d.id = ?
                """, (rs, rowNum) -> new WritebackSource(
                rs.getLong("draft_id"), rs.getLong("ticket_id"), rs.getString("draft_text"),
                rs.getString("external_ticket_id"),
                rs.getObject("connection_id", Long.class), rs.getString("status"),
                rs.getString("owner_actor_id"), rs.getString("reviewer_actor_id"),
                rs.getBoolean("review_queue")), draftId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private void requireWritebackAccess(WritebackSource source) {
        CurrentActor actor = actorProvider.currentActor();
        if (!accessPolicy.allowed(actor, ObjectAction.CONFIRM, source.ownerActorId(),
                source.reviewerActorId(), source.reviewQueue())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
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
                ON CONFLICT (ticket_id, context_type, source_reference, observed_at)
                DO UPDATE SET sanitized_payload = EXCLUDED.sanitized_payload,
                              expires_at = EXCLUDED.expires_at
                """, ticketId, type, reference, sanitized,
                contextTimestamp(observedAt), Timestamp.from(Instant.now().plus(Duration.ofHours(24))));
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

    private TicketUrgency slaUrgency(Instant dueAt) {
        SlaStatus status = slaStatus(dueAt);
        return status == SlaStatus.BREACHED ? TicketUrgency.CRITICAL
                : status == SlaStatus.AT_RISK ? TicketUrgency.HIGH : TicketUrgency.MEDIUM;
    }

    private String safeChannel(String channel) {
        return channel == null || channel.isBlank() ? "external" : channel.trim();
    }

    private Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Timestamp contextTimestamp(Instant value) {
        return value == null ? Timestamp.from(Instant.EPOCH) : Timestamp.from(value);
    }

    private void recordAudit(Long ticketId, String eventType, String creator, String actor,
                             String violationCode) {
        auditService.recordRequired(newAudit(ticketId, eventType, creator, actor, violationCode));
    }

    private SupportAuditLog newAudit(Long ticketId, String eventType, String creator, String actor,
                                     String violationCode) {
        return new SupportAuditLog(
                null, UUID.randomUUID().toString(), ticketId, eventType,
                null, null, null, null, null, null, null, creator, actor,
                null, null, null, null, null, "support-enterprise-v2.3", violationCode,
                null, null, null, null, null);
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
    private record WritebackSource(long draftId, long ticketId, String draftText,
                                   String externalTicketId, Long connectionId,
                                   String status, String ownerActorId,
                                   String reviewerActorId, boolean reviewQueue) { }
    private record PendingWriteback(long id, long draftId, long connectionId,
                                    String externalTicketId, String tokenDigest,
                                    Instant expiresAt, String payloadHash,
                                    String requestedBy, String draftText, long ticketId,
                                    String ownerActorId, String reviewerActorId,
                                    boolean reviewQueue) { }
    public record ConnectionCommand(String connectionKey, String displayName,
                                    SupportExternalProvider provider, String baseUrl,
                                    String secretRef, boolean enabled) { }
    public record ImportResult(int fetched, int created, int updated) { }
    public record SimilarTicket(long ticketId, String externalId, String customerMessage,
                                String status, String category, double similarity) { }
    public record QualityMetrics(long accepted, long editedAccepted, long rejected,
                                 long handedOff, long slaAtRisk, long slaBreached) { }
    public record WritebackCapability(boolean eligible) { }
    public record WritebackIntent(Long id, String status,
                                  String confirmationToken, Instant expiresAt) { }
    public record WritebackResult(long id, String status) { }
    public record WritebackStatus(long id, long draftId, String status,
                                  int attemptCount, Instant lastAttemptAt,
                                  String externalReceipt, String errorCategory) { }
    public enum WritebackResolution { COMPLETED, SAFE_TO_RETRY }
    public record ResolutionCommand(WritebackResolution resolution,
                                    String evidenceReference) { }
}
