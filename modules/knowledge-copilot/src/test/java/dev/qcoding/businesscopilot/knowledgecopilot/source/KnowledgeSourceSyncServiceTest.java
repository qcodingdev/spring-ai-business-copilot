package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalConnectionSecurityProperties;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeVisibilityScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Duration;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSourceSyncServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void importsUnmappedExternalAclAsAdminOnlyInsteadOfExpandingVisibility() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentUploadService uploadService = mock(DocumentUploadService.class);
        KnowledgeSourceConnection connection = new KnowledgeSourceConnection(
                7L, "sharepoint-ops", "运营知识库", KnowledgeSourceProvider.SHAREPOINT,
                "https://example.sharepoint.com", "site-1", "SHAREPOINT_TOKEN",
                Map.of("known-reviewers", KnowledgeVisibilityScope.HR_REVIEWER),
                KnowledgeVisibilityScope.ADMIN, true, "admin");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("knowledge_source_connections WHERE id")) {
                        return List.of(connection);
                    }
                    return List.of();
                });
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO knowledge_sync_runs"), eq(Long.class), any(Object[].class)))
                .thenReturn(99L);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        KnowledgeSourceAdapter adapter = new KnowledgeSourceAdapter() {
            @Override
            public boolean supports(KnowledgeSourceProvider provider) {
                return provider == KnowledgeSourceProvider.SHAREPOINT;
            }

            @Override
            public SourceBatch fetch(KnowledgeSourceConnection ignored, String cursor) {
                return new SourceBatch(List.of(new SourceItem(
                        "page-100", "退款政策.md", "text/markdown",
                        "企业退款政策正文".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "3", "etag-3", Instant.parse("2026-07-28T10:00:00Z"),
                        List.of("unknown-external-group"), false)), null, false);
            }
        };
        ExternalEndpointPolicy endpointPolicy = mock(ExternalEndpointPolicy.class);
        when(endpointPolicy.properties()).thenReturn(new ExternalConnectionSecurityProperties(
                List.of(), false, false, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(10), 1024, 2, 50, 16));
        KnowledgeSourceSyncService service = new KnowledgeSourceSyncService(
                jdbcTemplate, List.of(adapter), uploadService,
                () -> new CurrentActor("admin", Set.of(BusinessRole.ADMIN)),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                endpointPolicy);

        KnowledgeSourceSyncService.SyncResult result = service.synchronize(7L);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.created()).isEqualTo(1);
        verify(uploadService).ingestManagedDocument(
                eq("退款政策.md"), eq("text/markdown"), any(byte[].class),
                eq("external:sharepoint-ops"), any(), eq(KnowledgeVisibilityScope.ADMIN),
                eq("sharepoint"), eq("source:sharepoint-ops"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unchangedSourceRenewsCurrentDocumentLifecycle() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentUploadService uploadService = mock(DocumentUploadService.class);
        UUID logicalDocumentId = UUID.randomUUID();
        byte[] content = "企业退款政策正文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String contentHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        KnowledgeSourceConnection connection = new KnowledgeSourceConnection(
                7L, "sharepoint-ops", "运营知识库", KnowledgeSourceProvider.SHAREPOINT,
                "https://example.sharepoint.com", "site-1", "SHAREPOINT_TOKEN",
                Map.of(), KnowledgeVisibilityScope.ADMIN, true, "admin");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    if (sql.contains("knowledge_source_connections WHERE id")) {
                        return List.of(connection);
                    }
                    if (sql.contains("FROM knowledge_source_items")
                            && sql.contains("source_item_id = ?")) {
                        ResultSet resultSet = mock(ResultSet.class);
                        when(resultSet.getLong("id")).thenReturn(42L);
                        when(resultSet.getString("source_item_id")).thenReturn("page-100");
                        when(resultSet.getString("source_version")).thenReturn("3");
                        when(resultSet.getString("source_etag")).thenReturn("etag-3");
                        when(resultSet.getString("content_hash")).thenReturn(contentHash);
                        when(resultSet.getObject("logical_document_id", UUID.class))
                                .thenReturn(logicalDocumentId);
                        return List.of(mapper.mapRow(resultSet, 0));
                    }
                    return List.of();
                });
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO knowledge_sync_runs"), eq(Long.class), any(Object[].class)))
                .thenReturn(99L);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        KnowledgeSourceAdapter adapter = new KnowledgeSourceAdapter() {
            @Override
            public boolean supports(KnowledgeSourceProvider provider) {
                return provider == KnowledgeSourceProvider.SHAREPOINT;
            }

            @Override
            public SourceBatch fetch(KnowledgeSourceConnection ignored, String cursor) {
                return new SourceBatch(List.of(new SourceItem(
                        "page-100", "退款政策.md", "text/markdown", content,
                        "3", "etag-3", Instant.parse("2026-07-28T10:00:00Z"),
                        List.of(), false)), null, false);
            }
        };
        ExternalEndpointPolicy endpointPolicy = mock(ExternalEndpointPolicy.class);
        when(endpointPolicy.properties()).thenReturn(new ExternalConnectionSecurityProperties(
                List.of(), false, false, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(10), 1024, 2, 50, 16));
        KnowledgeSourceSyncService service = new KnowledgeSourceSyncService(
                jdbcTemplate, List.of(adapter), uploadService,
                () -> new CurrentActor("admin", Set.of(BusinessRole.ADMIN)),
                mock(ExternalSecretResolver.class), new ObjectMapper(), endpointPolicy);

        KnowledgeSourceSyncService.SyncResult result = service.synchronize(7L);

        assertThat(result.updated()).isZero();
        verify(uploadService).ingestManagedDocument(
                eq("退款政策.md"), eq("text/markdown"), eq(content),
                eq("external:sharepoint-ops"), eq(logicalDocumentId),
                eq(KnowledgeVisibilityScope.ADMIN), eq("sharepoint"),
                eq("source:sharepoint-ops"));
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("UPDATE knowledge_documents")
                        && sql.contains("expires_at = ?")
                        && sql.contains("visibility_scope = ?")
                        && sql.contains("enabled = CASE WHEN index_status = 'INDEXED'")
                        && sql.contains("conflict_status = 'NONE'")),
                eq("sharepoint-ops:page-100"), any(java.sql.Timestamp.class),
                any(java.sql.Timestamp.class), eq("ADMIN"), eq(logicalDocumentId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsASecondActiveSyncForTheSameConnection() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeSourceConnection connection = new KnowledgeSourceConnection(
                7L, "sharepoint-ops", "运营知识库", KnowledgeSourceProvider.SHAREPOINT,
                "https://example.sharepoint.com", "site-1", "SHAREPOINT_TOKEN",
                Map.of(), KnowledgeVisibilityScope.ADMIN, true, "admin");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> invocation.<String>getArgument(0)
                        .contains("knowledge_source_connections WHERE id")
                        ? List.of(connection) : List.of());
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO knowledge_sync_runs"), eq(Long.class), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("active sync"));
        KnowledgeSourceAdapter adapter = mock(KnowledgeSourceAdapter.class);
        when(adapter.supports(KnowledgeSourceProvider.SHAREPOINT)).thenReturn(true);
        KnowledgeSourceSyncService service = new KnowledgeSourceSyncService(
                jdbcTemplate, List.of(adapter), mock(DocumentUploadService.class),
                () -> new CurrentActor("admin", Set.of(BusinessRole.ADMIN)),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                mock(ExternalEndpointPolicy.class));

        assertThatThrownBy(() -> service.synchronize(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有同步任务正在运行");
        verify(adapter, never()).fetch(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullRecoverySyncIgnoresTheStoredIncrementalCursor() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeSourceConnection connection = new KnowledgeSourceConnection(
                7L, "sharepoint-ops", "运营知识库", KnowledgeSourceProvider.SHAREPOINT,
                "https://example.sharepoint.com", "site-1", "SHAREPOINT_TOKEN",
                Map.of(), KnowledgeVisibilityScope.ADMIN, true, "admin");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> invocation.<String>getArgument(0)
                        .contains("knowledge_source_connections WHERE id")
                        ? List.of(connection) : List.of());
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO knowledge_sync_runs"), eq(Long.class), any(Object[].class)))
                .thenReturn(99L);
        KnowledgeSourceAdapter adapter = mock(KnowledgeSourceAdapter.class);
        when(adapter.supports(KnowledgeSourceProvider.SHAREPOINT)).thenReturn(true);
        when(adapter.fetch(connection, null))
                .thenReturn(new KnowledgeSourceAdapter.SourceBatch(List.of(), null, false));
        ExternalEndpointPolicy endpointPolicy = mock(ExternalEndpointPolicy.class);
        when(endpointPolicy.properties()).thenReturn(new ExternalConnectionSecurityProperties(
                List.of(), false, false, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(10), 1024, 2, 50, 16));
        KnowledgeSourceSyncService service = new KnowledgeSourceSyncService(
                jdbcTemplate, List.of(adapter), mock(DocumentUploadService.class),
                () -> new CurrentActor("admin", Set.of(BusinessRole.ADMIN)),
                mock(ExternalSecretResolver.class), new ObjectMapper(), endpointPolicy);

        assertThat(service.synchronize(7L, true).status()).isEqualTo("COMPLETED");
        verify(adapter).fetch(connection, null);
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("error_category = 'STALE_SYNC_RECOVERED'")
                        && sql.contains("started_at <= ?")),
                eq(7L), any(Timestamp.class));
        verify(jdbcTemplate, never()).query(
                contains("SELECT cursor_after"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesFailedAndStaleDocumentIndexesInTheSourceRemediationQueue() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(contains("document.index_status"),
                any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<KnowledgeSourceSyncService.SourceIssue> mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    Instant updatedAt = Instant.parse("2026-08-20T01:00:00Z");
                    when(resultSet.getLong("id")).thenReturn(42L);
                    when(resultSet.getLong("connection_id")).thenReturn(7L);
                    when(resultSet.getString("display_name")).thenReturn("运营知识库");
                    when(resultSet.getString("source_item_id")).thenReturn("page-100");
                    when(resultSet.getString("sync_status")).thenReturn("CURRENT");
                    when(resultSet.getObject("document_id", Long.class)).thenReturn(88L);
                    when(resultSet.getString("index_status")).thenReturn("FAILED");
                    when(resultSet.getString("conflict_status")).thenReturn("NONE");
                    when(resultSet.getTimestamp("document_updated_at"))
                            .thenReturn(Timestamp.from(updatedAt));
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        KnowledgeSourceSyncService service = new KnowledgeSourceSyncService(
                jdbcTemplate, List.of(), mock(DocumentUploadService.class),
                () -> new CurrentActor("admin", Set.of(BusinessRole.ADMIN)),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                mock(ExternalEndpointPolicy.class));

        List<KnowledgeSourceSyncService.SourceIssue> issues = service.issues();

        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue.documentId()).isEqualTo(88L);
            assertThat(issue.indexStatus()).isEqualTo("FAILED");
            assertThat(issue.documentUpdatedAt())
                    .isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
        });
    }
}
