package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportGenerationServiceTest {

    private final ReportRequestPreparationService preparationService = mock(ReportRequestPreparationService.class);
    private final AiChatService aiChatService = mock(AiChatService.class);
    private final ReportDraftPersistenceService draftPersistenceService = mock(ReportDraftPersistenceService.class);
    private final ReportGenerationService service = new ReportGenerationService(preparationService, aiChatService,
            new PromptTemplateService(), new ReportPromptContextFactory(), new ReportGenerationOutputValidator(),
            new ReportOutputSanitizer(new SensitiveTextMasker()), draftPersistenceService);

    @Test
    void returnsReviewOnlyCandidateWhenEveryFactHasValidEvidence() {
        ReportRequestPreparationService.ReportRequestPreview preview = preview();
        when(preparationService.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(preview);
        when(aiChatService.modelName()).thenReturn("test-model");
        when(aiChatService.generateJsonWithMetadata(anyString(), eq(LlmReportOutput.class)))
                .thenReturn(invocation(validOutput("source-1")));
        when(draftPersistenceService.createDraft(any(), any(), eq("test-model"),
                any(), any(), anyString(), any()))
                .thenReturn(new ReportDraft(10L, 20L, validOutput("source-1"), ReportDraftStatus.DRAFTED, null,
                        "confirm-token", "digest", "operator-1", null,
                        Instant.parse("2026-07-11T12:00:00Z"), Instant.now(), Instant.now()));

        ReportDraftResponse response = service.generate(new ReportGenerateRequest(null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo("DRAFTED");
        assertThat(response.draftId()).isEqualTo(10L);
        assertThat(response.confirmationToken()).isEqualTo("confirm-token");
        assertThat(response.content().executiveSummary()).isEqualTo("Orders remained stable.");
        verify(aiChatService).generateJsonWithMetadata(
                org.mockito.ArgumentMatchers.contains("sourceId=source-1"), eq(LlmReportOutput.class));
    }

    @Test
    void storesOnlyReviewReasonsWhenOutputCitesAnUnknownSource() {
        when(preparationService.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(preview());
        when(aiChatService.modelName()).thenReturn("test-model");
        when(aiChatService.generateJsonWithMetadata(anyString(), eq(LlmReportOutput.class)))
                .thenReturn(invocation(validOutput("invented-source")));
        when(draftPersistenceService.createNeedsReviewDraft(
                any(), any(), eq("test-model"), any(), any(), anyString(), any()))
                .thenReturn(new ReportDraft(11L, 21L, null, ReportDraftStatus.NEEDS_REVIEW,
                        "Citation refers to a source outside the current request.", "review-token",
                        "digest", "operator-1", null,
                        Instant.parse("2026-07-11T12:00:00Z"), Instant.now(), Instant.now()));

        ReportDraftResponse response = service.generate(new ReportGenerateRequest(null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(response.draftId()).isEqualTo(11L);
        assertThat(response.content()).isNull();
        assertThat(response.reviewReasons()).isNotEmpty();
        assertThat(response.confirmationToken()).isEqualTo("review-token");
        verify(draftPersistenceService).createNeedsReviewDraft(
                any(), any(), eq("test-model"), any(), any(), anyString(), any());
    }

    @Test
    void recordsMetadataOnlyFailureWhenTheModelCallFails() {
        ReportRequestPreparationService.ReportRequestPreview preview = preview();
        when(preparationService.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(preview);
        when(aiChatService.modelName()).thenReturn("test-model");
        when(aiChatService.generateJsonWithMetadata(anyString(), eq(LlmReportOutput.class)))
                .thenThrow(new IllegalStateException("model offline"));

        assertThatThrownBy(() -> service.generate(new ReportGenerateRequest(null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model offline");

        verify(draftPersistenceService).recordGenerationFailure(
                eq(preview), eq("test-model"), any(), eq(null), anyString(), any());
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

    private AiInvocationResult<LlmReportOutput> invocation(LlmReportOutput output) {
        return new AiInvocationResult<>(output, new AiInvocationMetadata(
                "openai-compatible", "test-model", "request-1",
                10, 20, "stop", 25L));
    }
}
