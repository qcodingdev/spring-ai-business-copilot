package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeVisibilityScope;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 企业知识来源的幂等同步编排，源端删除只停用本地资料，不静默保留检索。 */
public class KnowledgeSourceSyncService {

    private static final Duration SOURCE_DOCUMENT_TTL = Duration.ofDays(30);

    private final JdbcTemplate jdbcTemplate;
    private final List<KnowledgeSourceAdapter> adapters;
    private final DocumentUploadService uploadService;
    private final CurrentActorProvider actorProvider;
    private final ExternalSecretResolver secretResolver;
    private final ObjectMapper objectMapper;
    private final ExternalEndpointPolicy endpointPolicy;
    private final Duration indexStaleAfter;

    public KnowledgeSourceSyncService(
            JdbcTemplate jdbcTemplate,
            List<KnowledgeSourceAdapter> adapters,
            DocumentUploadService uploadService,
            CurrentActorProvider actorProvider,
            ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy) {
        this(jdbcTemplate, adapters, uploadService, actorProvider, secretResolver,
                objectMapper, endpointPolicy, Duration.ofMinutes(15));
    }

    public KnowledgeSourceSyncService(
            JdbcTemplate jdbcTemplate,
            List<KnowledgeSourceAdapter> adapters,
            DocumentUploadService uploadService,
            CurrentActorProvider actorProvider,
            ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy,
            Duration indexStaleAfter) {
        this.jdbcTemplate = jdbcTemplate;
        this.adapters = List.copyOf(adapters);
        this.uploadService = uploadService;
        this.actorProvider = actorProvider;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
        this.indexStaleAfter = indexStaleAfter == null || indexStaleAfter.isZero()
                || indexStaleAfter.isNegative() ? Duration.ofMinutes(15) : indexStaleAfter;
    }

    public KnowledgeSourceConnection save(ConnectionCommand command) {
        if (command.provider() != KnowledgeSourceProvider.MOUNTED_DRIVE) {
            ExternalSecretResolver.validateRef(command.secretRef());
            endpointPolicy.validateBaseUrl(command.baseUrl());
        }
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_source_connections (
                    connection_key, display_name, provider, base_url, root_reference,
                    secret_ref, group_mapping, default_visibility, enabled, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (connection_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    root_reference = EXCLUDED.root_reference,
                    secret_ref = EXCLUDED.secret_ref,
                    group_mapping = EXCLUDED.group_mapping,
                    default_visibility = EXCLUDED.default_visibility,
                    enabled = EXCLUDED.enabled,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    updated_at = now()
                RETURNING id, connection_key, display_name, provider, base_url,
                          root_reference, secret_ref, group_mapping::text,
                          default_visibility, enabled, owner_actor_id
                """, this::mapConnection, command.connectionKey().trim(),
                command.displayName().trim(), command.provider().name(),
                command.provider() == KnowledgeSourceProvider.MOUNTED_DRIVE
                        ? "mounted://local" : command.baseUrl().trim(),
                trimToNull(command.rootReference()),
                command.provider() == KnowledgeSourceProvider.MOUNTED_DRIVE
                        ? "MOUNTED_DRIVE_NO_SECRET" : command.secretRef().trim(),
                json(command.groupMapping() == null ? Map.of() : command.groupMapping()),
                command.defaultVisibility().name(), command.enabled(), actorId);
    }

    public List<KnowledgeSourceConnection> connections() {
        return jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider, base_url,
                       root_reference, secret_ref, group_mapping::text,
                       default_visibility, enabled, owner_actor_id
                FROM knowledge_source_connections
                ORDER BY display_name
                """, this::mapConnection);
    }

    public SyncResult synchronize(long connectionId) {
        return synchronize(connectionId, false);
    }

    /** A confirmed full resync starts without the stored cursor and can repair unchanged stale items. */
    public SyncResult synchronize(long connectionId, boolean fullResync) {
        KnowledgeSourceConnection connection = requireConnection(connectionId);
        if (!connection.enabled()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "知识来源尚未启用");
        }
        KnowledgeSourceAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.supports(connection.provider()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STATE_CONFLICT, "当前来源适配器尚未装配"));
        String actorId = actorProvider.currentActor().actorId();
        String cursor = fullResync ? null : latestCursor(connectionId);
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_sync_runs (
                    connection_id, status, cursor_before, requested_by
                ) VALUES (?, 'RUNNING', ?, ?)
                RETURNING id
                """, Long.class, connectionId, cursor, actorId);
        int fetched = 0;
        int created = 0;
        int updated = 0;
        int deleted = 0;
        int conflicts = 0;
        boolean fullSnapshot = false;
        Set<String> seen = new HashSet<>();
        long externalStarted = System.nanoTime();
        try {
            int maxPages = endpointPolicy.properties().maxPages();
            int maxItems = endpointPolicy.properties().maxItems();
            for (int page = 0; page < maxPages; page++) {
                if (System.nanoTime() - externalStarted
                        > endpointPolicy.properties().taskTimeout().toNanos()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "企业知识来源同步超过整体超时限制");
                }
                KnowledgeSourceAdapter.SourceBatch batch = adapter.fetch(connection, cursor);
                fullSnapshot = fullSnapshot || batch.fullSnapshot();
                for (KnowledgeSourceAdapter.SourceItem item : batch.items()) {
                    fetched++;
                    if (fetched > maxItems) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "企业知识来源同步条目超过安全数量限制");
                    }
                    seen.add(item.sourceItemId());
                    Change change = apply(connection, item);
                    created += change.created() ? 1 : 0;
                    updated += change.updated() ? 1 : 0;
                    deleted += change.deleted() ? 1 : 0;
                    conflicts += change.conflict() ? 1 : 0;
                }
                cursor = batch.nextCursor();
                if (cursor == null || cursor.isBlank()) break;
                if (page == maxPages - 1) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "企业知识来源同步分页超过安全限制");
                }
            }
            if (fullSnapshot) {
                deleted += markMissingDeleted(connectionId, seen);
            }
            jdbcTemplate.update("""
                    UPDATE knowledge_sync_runs
                    SET status = 'COMPLETED', cursor_after = ?, fetched_count = ?,
                        created_count = ?, updated_count = ?, deleted_count = ?,
                        conflict_count = ?, finished_at = now()
                    WHERE id = ?
                    """, cursor, fetched, created, updated, deleted, conflicts, runId);
            return new SyncResult(runId, "COMPLETED", fetched, created, updated, deleted, conflicts);
        } catch (RuntimeException ex) {
            jdbcTemplate.update("""
                    UPDATE knowledge_sync_runs
                    SET status = 'FAILED', error_category = 'SOURCE_SYNC_FAILED',
                        fetched_count = ?, created_count = ?, updated_count = ?,
                        deleted_count = ?, conflict_count = ?, finished_at = now()
                    WHERE id = ?
                    """, fetched, created, updated, deleted, conflicts, runId);
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "企业知识来源同步失败，请检查连接和权限配置");
        }
    }

    public List<SourceIssue> issues() {
        Timestamp staleBefore = Timestamp.from(Instant.now().minus(indexStaleAfter));
        return jdbcTemplate.query("""
                SELECT item.id, connection.id AS connection_id, connection.display_name,
                       item.source_item_id,
                       item.sync_status, item.source_updated_at, item.last_synced_at,
                       document.id AS document_id, document.index_status,
                       document.expires_at, document.conflict_status,
                       document.updated_at AS document_updated_at
                FROM knowledge_source_items item
                JOIN knowledge_source_connections connection ON connection.id = item.connection_id
                LEFT JOIN knowledge_documents document
                  ON document.logical_document_id = item.logical_document_id
                 AND document.current_version = TRUE
                WHERE item.sync_status IN ('CONFLICT', 'FAILED', 'DELETED')
                   OR document.conflict_status <> 'NONE'
                   OR document.expires_at <= now()
                   OR document.index_status = 'FAILED'
                   OR (document.index_status IN ('PENDING', 'PROCESSING', 'RETRYABLE')
                       AND document.updated_at <= ?)
                ORDER BY item.updated_at DESC
                LIMIT 200
                """, (rs, rowNum) -> new SourceIssue(
                rs.getLong("id"), rs.getLong("connection_id"), rs.getString("display_name"),
                rs.getString("source_item_id"), rs.getString("sync_status"),
                rs.getObject("document_id", Long.class), rs.getString("index_status"),
                rs.getString("conflict_status"),
                toInstant(rs.getTimestamp("source_updated_at")),
                toInstant(rs.getTimestamp("last_synced_at")),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("document_updated_at"))), staleBefore);
    }

    private Change apply(KnowledgeSourceConnection connection, KnowledgeSourceAdapter.SourceItem item) {
        Optional<ExistingItem> existing = existing(connection.id(), item.sourceItemId());
        if (item.deleted()) {
            existing.ifPresent(value -> disable(value.logicalDocumentId()));
            upsertSourceItem(connection, item,
                    existing.map(ExistingItem::logicalDocumentId).orElse(null),
                    null, "DELETED");
            return new Change(false, false, true, false);
        }
        String contentHash = sha256(item.content());
        boolean unchanged = existing.isPresent() && contentHash.equals(existing.get().contentHash())
                && equal(item.etag(), existing.get().etag())
                && equal(item.version(), existing.get().version());
        UUID logicalDocumentId = existing.map(ExistingItem::logicalDocumentId)
                .orElseGet(() -> UUID.nameUUIDFromBytes(
                        (connection.connectionKey() + ":" + item.sourceItemId())
                                .getBytes(StandardCharsets.UTF_8)));
        KnowledgeVisibilityScope visibility = visibility(connection, item.allowedGroups());
        uploadService.ingestManagedDocument(
                item.fileName(), item.contentType(), item.content(),
                "external:" + connection.connectionKey(), logicalDocumentId, visibility,
                connection.provider().name().toLowerCase(java.util.Locale.ROOT),
                "source:" + connection.connectionKey());
        upsertSourceItem(connection, item, logicalDocumentId, contentHash,
                unchanged || existing.isEmpty() ? "CURRENT" : "UPDATED");
        jdbcTemplate.update("""
                UPDATE knowledge_documents
                SET source_item_ref = ?, source_updated_at = ?, expires_at = ?,
                    conflict_status = 'NONE',
                    enabled = CASE WHEN index_status = 'INDEXED' THEN TRUE ELSE enabled END,
                    updated_at = now()
                WHERE logical_document_id = ? AND current_version = TRUE
                """, connection.connectionKey() + ":" + item.sourceItemId(),
                timestamp(item.sourceUpdatedAt()),
                Timestamp.from(Instant.now().plus(SOURCE_DOCUMENT_TTL)),
                logicalDocumentId);
        if (unchanged) {
            return Change.NONE;
        }
        return new Change(existing.isEmpty(), existing.isPresent(), false, false);
    }

    private void upsertSourceItem(
            KnowledgeSourceConnection connection,
            KnowledgeSourceAdapter.SourceItem item,
            UUID logicalDocumentId,
            String contentHash,
            String status) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_source_items (
                    connection_id, source_item_id, source_version, source_etag,
                    source_updated_at, content_hash, acl_snapshot, visibility_scope,
                    logical_document_id, sync_status, deleted_at_source
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (connection_id, source_item_id) DO UPDATE SET
                    source_version = EXCLUDED.source_version,
                    source_etag = EXCLUDED.source_etag,
                    source_updated_at = EXCLUDED.source_updated_at,
                    content_hash = COALESCE(EXCLUDED.content_hash, knowledge_source_items.content_hash),
                    acl_snapshot = EXCLUDED.acl_snapshot,
                    visibility_scope = EXCLUDED.visibility_scope,
                    logical_document_id = COALESCE(EXCLUDED.logical_document_id,
                                                   knowledge_source_items.logical_document_id),
                    sync_status = EXCLUDED.sync_status,
                    deleted_at_source = EXCLUDED.deleted_at_source,
                    last_synced_at = now(),
                    updated_at = now()
                """, connection.id(), item.sourceItemId(), item.version(), item.etag(),
                timestamp(item.sourceUpdatedAt()), contentHash, json(item.allowedGroups()),
                visibility(connection, item.allowedGroups()).name(), logicalDocumentId, status,
                item.deleted() ? Timestamp.from(Instant.now()) : null);
    }

    private int markMissingDeleted(long connectionId, Set<String> seen) {
        List<ExistingItem> existing = jdbcTemplate.query("""
                SELECT id, source_item_id, source_version, source_etag, content_hash, logical_document_id
                FROM knowledge_source_items
                WHERE connection_id = ? AND sync_status <> 'DELETED'
                """, this::mapExisting, connectionId);
        int deleted = 0;
        for (ExistingItem item : existing) {
            if (!seen.contains(item.sourceItemId())) {
                disable(item.logicalDocumentId());
                jdbcTemplate.update("""
                        UPDATE knowledge_source_items
                        SET sync_status = 'DELETED', deleted_at_source = now(),
                            last_synced_at = now(), updated_at = now()
                        WHERE id = ?
                        """, item.id());
                deleted++;
            }
        }
        return deleted;
    }

    private void disable(UUID logicalDocumentId) {
        if (logicalDocumentId != null) {
            jdbcTemplate.update("""
                    UPDATE knowledge_documents
                    SET enabled = FALSE, conflict_status = 'SOURCE_NEWER', updated_at = now()
                    WHERE logical_document_id = ? AND current_version = TRUE
                    """, logicalDocumentId);
        }
    }

    private Optional<ExistingItem> existing(long connectionId, String sourceItemId) {
        List<ExistingItem> rows = jdbcTemplate.query("""
                SELECT id, source_item_id, source_version, source_etag, content_hash, logical_document_id
                FROM knowledge_source_items
                WHERE connection_id = ? AND source_item_id = ?
                """, this::mapExisting, connectionId, sourceItemId);
        return rows.stream().findFirst();
    }

    private ExistingItem mapExisting(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExistingItem(rs.getLong("id"), rs.getString("source_item_id"),
                rs.getString("source_version"), rs.getString("source_etag"),
                rs.getString("content_hash"),
                rs.getObject("logical_document_id", UUID.class));
    }

    private KnowledgeSourceConnection requireConnection(long id) {
        List<KnowledgeSourceConnection> rows = jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider, base_url,
                       root_reference, secret_ref, group_mapping::text,
                       default_visibility, enabled, owner_actor_id
                FROM knowledge_source_connections WHERE id = ?
                """, this::mapConnection, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private KnowledgeSourceConnection mapConnection(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        try {
            Map<String, KnowledgeVisibilityScope> mapping = objectMapper.readValue(
                    rs.getString("group_mapping"),
                    new TypeReference<Map<String, KnowledgeVisibilityScope>>() { });
            return new KnowledgeSourceConnection(
                    rs.getLong("id"), rs.getString("connection_key"),
                    rs.getString("display_name"),
                    KnowledgeSourceProvider.valueOf(rs.getString("provider")),
                    rs.getString("base_url"), rs.getString("root_reference"),
                    rs.getString("secret_ref"), mapping,
                    KnowledgeVisibilityScope.valueOf(rs.getString("default_visibility")),
                    rs.getBoolean("enabled"), rs.getString("owner_actor_id"));
        } catch (JacksonException ex) {
            throw new IllegalStateException("知识来源权限映射读取失败", ex);
        }
    }

    private String latestCursor(long connectionId) {
        List<String> values = jdbcTemplate.query("""
                SELECT cursor_after FROM knowledge_sync_runs
                WHERE connection_id = ? AND status = 'COMPLETED' AND cursor_after IS NOT NULL
                ORDER BY finished_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), connectionId);
        return values.isEmpty() ? null : values.getFirst();
    }

    private KnowledgeVisibilityScope visibility(
            KnowledgeSourceConnection connection, List<String> allowedGroups) {
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            return connection.defaultVisibility();
        }
        List<KnowledgeVisibilityScope> mapped = allowedGroups.stream()
                .map(connection.groupMapping()::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (mapped.isEmpty()) {
            return KnowledgeVisibilityScope.ADMIN;
        }
        if (mapped.contains(KnowledgeVisibilityScope.ALL)) return KnowledgeVisibilityScope.ALL;
        if (mapped.contains(KnowledgeVisibilityScope.HR_REVIEWER)) {
            return KnowledgeVisibilityScope.HR_REVIEWER;
        }
        return KnowledgeVisibilityScope.ADMIN;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("知识来源同步对象序列化失败", ex);
        }
    }

    private boolean equal(String left, String right) {
        return java.util.Objects.equals(left, right);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record ExistingItem(long id, String sourceItemId, String version, String etag,
                                String contentHash, UUID logicalDocumentId) { }
    private record Change(boolean created, boolean updated, boolean deleted, boolean conflict) {
        private static final Change NONE = new Change(false, false, false, false);
    }

    public record ConnectionCommand(
            String connectionKey,
            String displayName,
            KnowledgeSourceProvider provider,
            String baseUrl,
            String rootReference,
            String secretRef,
            Map<String, KnowledgeVisibilityScope> groupMapping,
            KnowledgeVisibilityScope defaultVisibility,
            boolean enabled) { }
    public record SyncResult(Long runId, String status, int fetched, int created,
                             int updated, int deleted, int conflicts) { }
    public record SourceIssue(long itemId, long connectionId, String connectionName, String sourceItemId,
                              String syncStatus, Long documentId, String indexStatus,
                              String conflictStatus, Instant sourceUpdatedAt,
                              Instant lastSyncedAt, Instant expiresAt,
                              Instant documentUpdatedAt) { }
}
