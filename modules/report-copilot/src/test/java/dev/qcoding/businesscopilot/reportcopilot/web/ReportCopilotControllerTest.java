package dev.qcoding.businesscopilot.reportcopilot.web;

import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftConfirmationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportCopilotControllerTest {

    private final ReportSourcePreviewService previewService = mock(ReportSourcePreviewService.class);
    private final ReportRequestPreparationService requestPreparationService = mock(ReportRequestPreparationService.class);
    private final ReportGenerationService generationService = mock(ReportGenerationService.class);
    private final ReportDraftConfirmationService confirmationService = mock(ReportDraftConfirmationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ReportCopilotController(previewService, requestPreparationService, generationService, confirmationService)).build();
    }

    @Test
    void returnsTheSanitizedEvidencePack() throws Exception {
        ReportSource source = new ReportSource("source-1", ReportSourceType.METRIC, "Weekly metrics",
                "Orders: 1284", "a".repeat(64), Map.of("period", "2026-W27"));
        when(previewService.preview()).thenReturn(new ReportSourcePreviewService.ReportSourcePreview(
                Instant.parse("2026-07-10T09:00:00Z"), List.of(source)));

        mockMvc.perform(get("/api/report-copilot/sample-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sources[0].sourceId").value("source-1"))
                .andExpect(jsonPath("$.data.sources[0].sourceHash").value("a".repeat(64)));
    }

    @Test
    void returnsNormalizedUserProvidedEvidence() throws Exception {
        ReportSource source = new ReportSource("source-2", ReportSourceType.TASK, "Release validation",
                "Status: BLOCKED", "b".repeat(64), Map.of("status", "BLOCKED"));
        var preview = new ReportRequestPreparationService.ReportRequestPreview(ReportType.TEAM_WEEKLY,
                new ReportPeriod(java.time.LocalDate.of(2026, 7, 6), java.time.LocalDate.of(2026, 7, 10)),
                "Delivery weekly", List.of(source));
        when(requestPreparationService.prepare(any(ReportGenerateRequest.class))).thenReturn(preview);

        mockMvc.perform(post("/api/report-copilot/source-previews")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reportType":"TEAM_WEEKLY","period":{"periodStart":"2026-07-06","periodEnd":"2026-07-10"},
                                 "title":"Delivery weekly","metrics":[],"tasks":[],"meetingNotes":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportType").value("TEAM_WEEKLY"))
                .andExpect(jsonPath("$.data.sources[0].sourceId").value("source-2"));
    }
}
