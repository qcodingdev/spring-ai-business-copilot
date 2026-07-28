package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportEnterpriseServiceTest {

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
                jdbcTemplate, generationService, mock(), mock(ExternalSecretResolver.class),
                new ObjectMapper(), org.springframework.web.client.RestClient.builder());

        ReportDraftResponse result = service.generate(new ReportEnterpriseService.GenerateCommand(
                ReportType.BUSINESS_WEEKLY, draft.period(), "客服经营周报",
                new ReportEnterpriseService.SourceSelection(List.of(), List.of(), true, null),
                "weekly-ops", "v1"));

        assertThat(result.status()).isEqualTo("NEEDS_REVIEW");
        ArgumentCaptor<ReportGenerateRequest> request =
                ArgumentCaptor.forClass(ReportGenerateRequest.class);
        verify(generationService).generate(request.capture());
        assertThat(request.getValue().importedSources()).singleElement().satisfies(source -> {
            assertThat(source.title()).isEqualTo("客服质量统计");
            assertThat(source.content()).contains("\"total\":120", "\"sla_breached\":1");
        });
    }

    @Test
    void consumesDataHandoffOnlyAfterDraftGenerationSucceeds() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReportGenerationService generationService = mock(ReportGenerationService.class);
        ReportDraftResponse draft = new ReportDraftResponse(
                89L, ReportType.BUSINESS_WEEKLY,
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28)),
                "经营简报", "DRAFT", null, List.of(), null, null, "test-model");
        when(generationService.generate(any(ReportGenerateRequest.class))).thenReturn(draft);
        ReportEnterpriseService service = new ReportEnterpriseService(
                jdbcTemplate, generationService, mock(), mock(ExternalSecretResolver.class),
                new ObjectMapper(), org.springframework.web.client.RestClient.builder());

        service.generate(new ReportEnterpriseService.GenerateCommand(
                ReportType.BUSINESS_WEEKLY, draft.period(), "经营简报",
                new ReportEnterpriseService.SourceSelection(
                        List.of(), List.of("data-result-20260728"), false, null),
                "operating-brief", "v1"));

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("SET status = 'CONSUMED'"),
                eq("data-result-20260728"));
    }
}
