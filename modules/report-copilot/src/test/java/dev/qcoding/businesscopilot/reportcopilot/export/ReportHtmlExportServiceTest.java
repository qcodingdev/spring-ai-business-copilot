package dev.qcoding.businesscopilot.reportcopilot.export;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportHtmlExportServiceTest {

    @Test
    void exportsOnlyEscapedServerRenderedHtml() {
        ReportDraftRepository repository = mock(ReportDraftRepository.class);
        ReportAuditService auditService = mock(ReportAuditService.class);
        CurrentActorProvider actorProvider = () ->
                new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR));
        LlmReportOutput content = new LlmReportOutput(
                "<script>alert(1)</script>", List.of("source-1"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ReportDraft draft = new ReportDraft(7L, 8L, content, ReportDraftStatus.CONFIRMED,
                null, null, null, "operator-1", "operator-1",
                Instant.now().plusSeconds(60), Instant.now(), Instant.now());
        when(repository.findById(7L)).thenReturn(Optional.of(draft));
        ReportHtmlExportService service = new ReportHtmlExportService(
                repository, properties(), auditService, actorProvider, new DefaultObjectAccessPolicy());

        String html = service.export(7L);

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).doesNotContain("<script>");
    }

    private static ReportCopilotProperties properties() {
        return new ReportCopilotProperties(true, 31, 10, 4000, 4, 4, 4,
                Duration.ofMinutes(30), Set.of(ReportType.TEAM_WEEKLY), true);
    }
}
