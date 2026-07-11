package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDraftPersistenceServiceTest {

    private final ReportDraftRepository repository = mock(ReportDraftRepository.class);
    private final ReportAuditService auditService = mock(ReportAuditService.class);
    private final ReportDraftPersistenceService service = new ReportDraftPersistenceService(repository, auditService, properties());

    @Test
    void persistsNeedsReviewWithoutAnyUntrustedReportContent() {
        var preview = mock(ReportRequestPreparationService.ReportRequestPreview.class);
        when(preview.sources()).thenReturn(List.of(mock(dev.qcoding.businesscopilot.reportcopilot.source.ReportSource.class)));
        List<String> reasons = List.of("Metric highlight does not exactly match a cited metric source.");
        var draft = new ReportDraft(11L, 21L, null, ReportDraftStatus.NEEDS_REVIEW, reasons.getFirst(),
                "review-token", Instant.now().plusSeconds(60), Instant.now(), Instant.now());
        when(repository.saveNeedsReview(eq(preview), eq(reasons), eq("test-model"), any())).thenReturn(draft);

        var result = service.createNeedsReviewDraft(preview, reasons, "test-model");

        assertThat(result.status()).isEqualTo(ReportDraftStatus.NEEDS_REVIEW);
        assertThat(result.content()).isNull();
        ArgumentCaptor<ReportAuditLog> audit = ArgumentCaptor.forClass(ReportAuditLog.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("NEEDS_REVIEW");
        assertThat(audit.getValue().citedSourceIds()).isEmpty();
        assertThat(audit.getValue().errorMessage()).doesNotContain("Metric highlight");
    }

    private static ReportCopilotProperties properties() {
        return new ReportCopilotProperties(true, 31, 10, 4000, 4, 4, 4, Duration.ofMinutes(30),
                Set.of(ReportType.TEAM_WEEKLY), true);
    }
}
