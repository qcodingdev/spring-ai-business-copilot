package dev.qcoding.businesscopilot.reportcopilot.export;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportItem;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportMarkdownExportServiceTest {

    private final ReportDraftRepository repository = mock(ReportDraftRepository.class);
    private final ReportMarkdownExportService service = new ReportMarkdownExportService(repository, properties());

    @Test
    void rendersOnlyConfirmedDraftsAndEscapesModelMarkdown() {
        when(repository.findById(10L)).thenReturn(Optional.of(draft(ReportDraftStatus.CONFIRMED)));

        String markdown = service.export(10L);

        assertThat(markdown).contains("## Executive Summary");
        assertThat(markdown).contains("\\[untrusted\\]");
        assertThat(markdown).contains("&lt;script&gt;");
    }

    @Test
    void rejectsDraftsThatAreNotConfirmed() {
        when(repository.findById(10L)).thenReturn(Optional.of(draft(ReportDraftStatus.DRAFTED)));

        assertThatThrownBy(() -> service.export(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CONFIRMED");
    }

    private ReportDraft draft(ReportDraftStatus status) {
        LlmReportOutput content = new LlmReportOutput("[untrusted] <script>", List.of("source-1"), List.of(),
                List.of(new ReportItem("Done", List.of("source-1"))), List.of(), List.of(), List.of(), List.of());
        return new ReportDraft(10L, 20L, content, status, null, null, null, Instant.now(), Instant.now());
    }

    private static ReportCopilotProperties properties() {
        return new ReportCopilotProperties(true, 31, 10, 4000, 4, 4, 4, Duration.ofMinutes(30),
                Set.of(ReportType.TEAM_WEEKLY), true);
    }
}
