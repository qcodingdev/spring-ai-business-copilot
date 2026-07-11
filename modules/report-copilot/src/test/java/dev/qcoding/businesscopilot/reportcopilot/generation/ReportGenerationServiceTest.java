package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportGenerationServiceTest {

    private final ReportRequestPreparationService preparationService = mock(ReportRequestPreparationService.class);
    private final AiChatService aiChatService = mock(AiChatService.class);
    private final ReportGenerationService service = new ReportGenerationService(preparationService, aiChatService,
            new PromptTemplateService(), new ReportPromptContextFactory(), new ReportGenerationOutputValidator(),
            new ReportOutputSanitizer(new SensitiveTextMasker()));

    @Test
    void returnsReviewOnlyCandidateWhenEveryFactHasValidEvidence() {
        ReportRequestPreparationService.ReportRequestPreview preview = preview();
        when(preparationService.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(preview);
        when(aiChatService.modelName()).thenReturn("test-model");
        when(aiChatService.generateJson(anyString(), eq(LlmReportOutput.class))).thenReturn(validOutput("source-1"));

        ReportDraftResponse response = service.generate(new ReportGenerateRequest(null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(response.content().executiveSummary()).isEqualTo("Orders remained stable.");
        verify(aiChatService).generateJson(org.mockito.ArgumentMatchers.contains("sourceId=source-1"), eq(LlmReportOutput.class));
    }

    @Test
    void rejectsOutputThatCitesAnUnknownSource() {
        when(preparationService.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(preview());
        when(aiChatService.modelName()).thenReturn("test-model");
        when(aiChatService.generateJson(anyString(), eq(LlmReportOutput.class))).thenReturn(validOutput("invented-source"));

        ReportDraftResponse response = service.generate(new ReportGenerateRequest(null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.content()).isNull();
        assertThat(response.reviewReasons()).isNotEmpty();
    }

    private ReportRequestPreparationService.ReportRequestPreview preview() {
        ReportSource source = new ReportSource("source-1", ReportSourceType.METRIC, "Orders",
                "Value: 1284 orders", "a".repeat(64),
                Map.of("name", "Orders", "value", "1284", "unit", "orders"));
        return new ReportRequestPreparationService.ReportRequestPreview(ReportType.TEAM_WEEKLY,
                new ReportPeriod(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10)), "Delivery weekly", List.of(source));
    }

    private LlmReportOutput validOutput(String sourceId) {
        return new LlmReportOutput("Orders remained stable.", List.of(sourceId),
                List.of(new MetricHighlight("Orders", "1284", "orders", "Orders remained stable.", List.of(sourceId))),
                List.of(), List.of(), List.of(), List.of(), List.of(new ReportCitation(sourceId, "Metric source")));
    }
}
