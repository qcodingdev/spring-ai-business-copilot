package dev.qcoding.businesscopilot.supportcopilot.integration;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketSentiment;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketUrgency;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicket;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportEnterpriseServiceTest {

    @Test
    void exposesWritebackOnlyForConfirmedExternalDrafts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(42L)))
                .thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("draft_id")).thenReturn(42L);
                    when(rs.getString("draft_text")).thenReturn("reply");
                    when(rs.getString("external_ticket_id")).thenReturn("T-42");
                    when(rs.getObject("connection_id", Long.class)).thenReturn(5L);
                    when(rs.getString("status")).thenReturn("CONFIRMED");
                    when(rs.getString("owner_actor_id")).thenReturn("operator-1");
                    when(rs.getBoolean("review_queue")).thenReturn(false);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        SupportEnterpriseService service = new SupportEnterpriseService(
                jdbcTemplate, mock(SupportTicketRepository.class), List.of(),
                () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
                new ConfirmationTokenService(), mock(ExternalSecretResolver.class),
                new SensitiveTextMasker(), new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                new DefaultObjectAccessPolicy(), mock(SupportAuditService.class));

        assertThat(service.writebackCapability(42L).eligible()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsExternalTicketAsMaskedReadOnlyContextAndFlagsSlaRisk() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SupportTicketRepository repository = mock(SupportTicketRepository.class);
        SupportExternalConnection connection = new SupportExternalConnection(
                5L, "jsm-prod", "JSM 工单", SupportExternalProvider.JIRA_SERVICE_MANAGEMENT,
                "https://jira.example.com", "JSM_TOKEN", true, "admin");
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("FROM support_external_connections"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn((List) List.of(connection));
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("INSERT INTO support_tickets"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn((List) List.of(42L));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(repository.save(any(SupportTicket.class))).thenReturn(new SupportTicket(
                42L, "JSM-100", "masked", "JSM", TicketCategory.OTHER,
                TicketSentiment.NEUTRAL, TicketUrgency.HIGH, SupportTicketStatus.RECEIVED,
                "operator-1", Instant.now(), Instant.now()));

        SupportExternalAdapter adapter = new SupportExternalAdapter() {
            @Override
            public boolean supports(SupportExternalProvider provider) {
                return provider == SupportExternalProvider.JIRA_SERVICE_MANAGEMENT;
            }

            @Override
            public List<ExternalTicket> fetchRecent(
                    SupportExternalConnection ignored, int limit) {
                return List.of(new ExternalTicket(
                        "JSM-100", "客户电话 13812345678，订单无法使用",
                        "JSM", Instant.now(), Instant.now().plusSeconds(3600),
                        Map.of("email", "alice@example.com"),
                        Map.of("orderId", "ORDER-100"),
                        Map.of("status", "degraded")));
            }

            @Override
            public void writeConfirmedDraft(
                    SupportExternalConnection ignored, String externalTicketId,
                    String draft, String idempotencyKey) {
                throw new AssertionError("只读导入不应触发回写");
            }
        };
        SupportEnterpriseService service = new SupportEnterpriseService(
                jdbcTemplate, repository, List.of(adapter),
                () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
                new ConfirmationTokenService(), mock(ExternalSecretResolver.class),
                new SensitiveTextMasker(), new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                new DefaultObjectAccessPolicy(), mock(SupportAuditService.class));

        SupportEnterpriseService.ImportResult result = service.importRecent(5L, 20);

        assertThat(result).isEqualTo(new SupportEnterpriseService.ImportResult(1, 1, 0));
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.contains("INSERT INTO support_tickets"),
                any(RowMapper.class), values.capture());
        assertThat(values.getValue()[1].toString())
                .contains("138****5678")
                .doesNotContain("13812345678");
        assertThat(values.getValue()[3]).isEqualTo(TicketUrgency.HIGH.name());
    }
}
