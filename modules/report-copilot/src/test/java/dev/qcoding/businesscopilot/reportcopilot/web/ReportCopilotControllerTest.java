package dev.qcoding.businesscopilot.reportcopilot.web;

import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourcePreviewService;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportCopilotControllerTest {

    private final ReportSourcePreviewService previewService = mock(ReportSourcePreviewService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReportCopilotController(previewService)).build();
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
}
