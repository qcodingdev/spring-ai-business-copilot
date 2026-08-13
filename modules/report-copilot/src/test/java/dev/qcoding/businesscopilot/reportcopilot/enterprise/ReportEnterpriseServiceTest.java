package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportDraftResponse;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportEnterpriseServiceTest {

    private CurrentActorProvider actorProvider() {
        return () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR));
    }

    @SuppressWarnings("unchecked")
    private void stubClaimedHandoff(JdbcTemplate jdbcTemplate) throws Exception {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(), any(), any())).thenAnswer(invocation -> {
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("title")).thenReturn("经营数据");
            when(rs.getString("source_reference")).thenReturn("data-result");
            when(rs.getString("rows_json")).thenReturn("[{\"count\":1}]");
            when(rs.getInt("row_count")).thenReturn(1);
            when(rs.getTimestamp("created_at")).thenReturn(
                    java.sql.Timestamp.from(java.time.Instant.now()));
            org.springframework.jdbc.core.RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    @Test
    void rejectsInvalidScheduleInputAsValidationError() {
        ReportEnterpriseService service = new ReportEnterpriseService(
                mock(JdbcTemplate.class), mock(ReportGenerationService.class), actorProvider(),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory.class));

        ReportEnterpriseService.ScheduleCommand command = new ReportEnterpriseService.ScheduleCommand(
                "weekly-ops", ReportType.BUSINESS_WEEKLY, "经营周报 {date}",
                "not-a-cron", "Invalid/Timezone", "weekly-ops", "v1",
                new ReportEnterpriseService.SourceSelection(List.of(), List.of(), true, null), true);

        assertThatThrownBy(() -> service.saveSchedule(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void rejectsOneShotDataHandoffAsAScheduledSource() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReportEnterpriseService service = new ReportEnterpriseService(
                jdbcTemplate, mock(ReportGenerationService.class), actorProvider(),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory.class));
        ReportEnterpriseService.ScheduleCommand command = new ReportEnterpriseService.ScheduleCommand(
                "weekly-ops", ReportType.BUSINESS_WEEKLY, "经营周报 {date}",
                "0 0 9 ? * MON", "Asia/Shanghai", "weekly-ops", "v1",
                new ReportEnterpriseService.SourceSelection(
                        List.of(), List.of("data-result:one-shot"), false, null), true);

        assertThatThrownBy(() -> service.saveSchedule(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("一次性 Data 结果交接");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsConnectionProviderWithoutAnExecutableAdapter() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var endpointPolicy = mock(
                dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class);
        ReportEnterpriseService service = new ReportEnterpriseService(
                jdbcTemplate, mock(ReportGenerationService.class), actorProvider(),
                mock(ExternalSecretResolver.class), new ObjectMapper(), endpointPolicy,
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory.class));

        assertThatThrownBy(() -> service.saveConnection(
                new ReportEnterpriseService.ConnectionCommand(
                        "legacy-data", "旧数据来源",
                        ReportEnterpriseService.Provider.DATA_QUERY,
                        "https://reports.example.test", null, true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("没有可执行适配器");
        verifyNoInteractions(jdbcTemplate, endpointPolicy);
    }

    @Test
    void aggregatesSupportMetricsIntoDraftWithoutPublishingIt() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReportGenerationService generationService = mock(ReportGenerationService.class);
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(java.util.Map.of(
                "total", 120L,
                "closed", 100L,
                "handed_off", 8L,
                "sla_at_risk", 3L,
                "sla_breached", 1L));
        ReportDraftResponse draft = new ReportDraftResponse(
                88L, ReportType.BUSINESS_WEEKLY,
                new ReportPeriod(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 26)),
                "客服经营周报", "NEEDS_REVIEW", null,
                List.of("人工确认后才能发布"), "confirm-token", "2026-07-28T12:00:00Z", "test-model");
        when(generationService.generate(any(ReportGenerateRequest.class))).thenReturn(draft);
        ReportEnterpriseService service = new ReportEnterpriseService(
                jdbcTemplate, generationService, actorProvider(), mock(ExternalSecretResolver.class),
                new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory.class));

        ReportDraftResponse result = service.generate(new ReportEnterpriseService.GenerateCommand(
                ReportType.BUSINESS_WEEKLY, draft.period(), "客服经营周报",
                new ReportEnterpriseService.SourceSelection(List.of(), List.of(), true, null),
                "weekly-ops", "v1"));

        assertThat(result.status()).isEqualTo("NEEDS_REVIEW");
        ArgumentCaptor<ReportGenerateRequest> request =
                ArgumentCaptor.forClass(ReportGenerateRequest.class);
        verify(generationService).generate(request.capture());
        assertThat(request.getValue().importedSources()).hasSize(5);
        assertThat(request.getValue().importedSources())
                .allSatisfy(source -> assertThat(source.attributes())
                        .containsKeys("name", "value", "unit"));
        assertThat(request.getValue().importedSources())
                .anySatisfy(source -> assertThat(source.attributes())
                        .containsEntry("name", "support.total")
                        .containsEntry("value", "120"));
    }

    @Test
    void consumesDataHandoffOnlyAfterDraftGenerationSucceeds() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReportGenerationService generationService = mock(ReportGenerationService.class);
        ReportDraftResponse draft = new ReportDraftResponse(
                89L, ReportType.BUSINESS_WEEKLY,
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28)),
                "经营简报", "DRAFTED",
                new dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput(
                        "已生成", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                List.of(), null, null, "test-model");
        when(generationService.generate(any(ReportGenerateRequest.class))).thenReturn(draft);
        stubClaimedHandoff(jdbcTemplate);
        ReportEnterpriseService service = new ReportEnterpriseService(
                jdbcTemplate, generationService, actorProvider(), mock(ExternalSecretResolver.class),
                new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory.class));

        service.generate(new ReportEnterpriseService.GenerateCommand(
                ReportType.BUSINESS_WEEKLY, draft.period(), "经营简报",
                new ReportEnterpriseService.SourceSelection(
                        List.of(), List.of("data-result-20260728"), false, null),
                "operating-brief", "v1"));

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("SET status = 'CONSUMED'"),
                any(java.util.UUID.class), eq("operator-1"));
    }

    @Test
    void keepsDataHandoffReadyWhenGeneratedDraftNeedsReview() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReportGenerationService generationService = mock(ReportGenerationService.class);
        ReportDraftResponse draft = new ReportDraftResponse(
                90L, ReportType.BUSINESS_WEEKLY,
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28)),
                "经营简报", "NEEDS_REVIEW", null,
                List.of("证据不一致"), null, null, "test-model");
        when(generationService.generate(any(ReportGenerateRequest.class))).thenReturn(draft);
        stubClaimedHandoff(jdbcTemplate);
        ReportEnterpriseService service = new ReportEnterpriseService(
                jdbcTemplate, generationService, actorProvider(), mock(ExternalSecretResolver.class),
                new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory.class));

        service.generate(new ReportEnterpriseService.GenerateCommand(
                ReportType.BUSINESS_WEEKLY, draft.period(), "经营简报",
                new ReportEnterpriseService.SourceSelection(
                        List.of(), List.of("data-result-review"), false, null),
                "operating-brief", "v1"));

        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.contains("SET status = 'CONSUMED'"),
                any(java.util.UUID.class), eq("operator-1"));
    }
}
