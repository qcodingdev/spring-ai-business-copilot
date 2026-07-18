package dev.qcoding.businesscopilot.reportcopilot.export;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditLog;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportItem;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportMarkdownExportServiceTest {

    private final ReportDraftRepository repository = mock(ReportDraftRepository.class);
    private final ReportAuditService auditService = mock(ReportAuditService.class);
    private final ReportMarkdownExportService service = new ReportMarkdownExportService(
            repository, properties(), auditService,
            () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
            new DefaultObjectAccessPolicy());

    @Test
    void rendersOnlyConfirmedDraftsAndEscapesModelMarkdown() {
        when(repository.findById(10L)).thenReturn(Optional.of(draft(ReportDraftStatus.CONFIRMED)));

        String markdown = service.export(10L);

        assertThat(markdown).contains("## 执行摘要");
        assertThat(markdown).contains("\\[untrusted\\]");
        assertThat(markdown).contains("&lt;script&gt;");
        ArgumentCaptor<ReportAuditLog> audit = ArgumentCaptor.forClass(ReportAuditLog.class);
        verify(auditService).recordRequired(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("EXPORTED");
        assertThat(audit.getValue().status()).isEqualTo("CONFIRMED");
        assertThat(audit.getValue().creatorActorId()).isEqualTo("operator-1");
        assertThat(audit.getValue().actionActorId()).isEqualTo("operator-1");
    }

    @Test
    void rejectsDraftsThatAreNotConfirmed() {
        when(repository.findById(10L)).thenReturn(Optional.of(draft(ReportDraftStatus.DRAFTED)));

        assertThatThrownBy(() -> service.export(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有已确认的报告草稿可以导出");
    }

    private ReportDraft draft(ReportDraftStatus status) {
        LlmReportOutput content = new LlmReportOutput("[untrusted] <script>", List.of("source-1"), List.of(),
                List.of(new ReportItem("Done", List.of("source-1"))), List.of(), List.of(), List.of(), List.of());
        return new ReportDraft(
                10L, 20L, content, status, null, null, null,
                "operator-1", null, Instant.now().plusSeconds(60),
                Instant.now(), Instant.now());
    }

    private static ReportCopilotProperties properties() {
        return new ReportCopilotProperties(true, 31, 10, 4000, 4, 4, 4, Duration.ofMinutes(30),
                Set.of(ReportType.TEAM_WEEKLY), true);
    }
}
