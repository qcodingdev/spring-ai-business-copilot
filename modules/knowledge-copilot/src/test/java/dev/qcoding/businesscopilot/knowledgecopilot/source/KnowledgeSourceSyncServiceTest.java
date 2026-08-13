package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalConnectionSecurityProperties;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeVisibilityScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}
