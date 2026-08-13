package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportOutputSanitizer;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportActionItem;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportActionItemOrigin;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportCitation;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDraftConfirmationServiceTest {

    private final ReportDraftRepository repository = mock(ReportDraftRepository.class);
    private final ReportAuditService auditService = mock(ReportAuditService.class);
    private final ConfirmationTokenService tokenService = new ConfirmationTokenService();
    private final ReportOutputSanitizer outputSanitizer = mock(ReportOutputSanitizer.class);
    private ReportDraftConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new ReportDraftConfirmationService(
                repository, auditService,
                () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
                new DefaultObjectAccessPolicy(), tokenService, outputSanitizer);
    }

    @Test
    void ownerConfirmsDraftBoundToDigestAndExpectedState() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        when(repository.findById(10L)).thenReturn(Optional.of(
                draft(10L, ReportDraftStatus.DRAFTED, token.digest(), Instant.now().plusSeconds(60))));
        when(repository.transitionStatus(
                10L, ReportDraftStatus.DRAFTED, ReportDraftStatus.CONFIRMED, "operator-1"))
                .thenReturn(true);

        var result = service.confirm(10L, token.rawToken());

        assertThat(result.status()).isEqualTo(ReportDraftStatus.CONFIRMED);
        verify(repository).transitionStatus(
                10L, ReportDraftStatus.DRAFTED, ReportDraftStatus.CONFIRMED, "operator-1");
        verify(auditService).recordRequired(any());
    }

    @Test
    void rejectsExpiredOrWrongToken() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        when(repository.findById(10L)).thenReturn(Optional.of(
                draft(10L, ReportDraftStatus.DRAFTED, token.digest(), Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> service.confirm(10L, token.rawToken()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.confirm(10L, "wrong"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ownerCancelsNeedsReviewDraft() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        when(repository.findById(10L)).thenReturn(Optional.of(
                draft(10L, ReportDraftStatus.NEEDS_REVIEW, token.digest(), Instant.now().plusSeconds(60))));
        when(repository.transitionStatus(
                10L, ReportDraftStatus.NEEDS_REVIEW, ReportDraftStatus.CANCELED, "operator-1"))
                .thenReturn(true);

        assertThat(service.cancel(10L, token.rawToken()).status())
                .isEqualTo(ReportDraftStatus.CANCELED);
    }

    @Test
    void savesHumanTextEditWithoutChangingEvidenceShape() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        LlmReportOutput original = report("原始摘要", "原始风险", "source-1");
        LlmReportOutput edited = report("人工摘要", "人工修订风险", "source-1");
        when(repository.findById(10L)).thenReturn(Optional.of(
                draft(10L, ReportDraftStatus.DRAFTED, token.digest(),
                        Instant.now().plusSeconds(60), original)));
        when(outputSanitizer.sanitize(edited)).thenReturn(edited);
        when(repository.updateContent(10L, ReportDraftStatus.DRAFTED, edited, "operator-1"))
                .thenReturn(true);

        var result = service.edit(10L, token.rawToken(), edited);

        assertThat(result.content().executiveSummary()).isEqualTo("人工摘要");
        verify(repository).updateContent(10L, ReportDraftStatus.DRAFTED, edited, "operator-1");
        verify(auditService).recordRequired(any());
    }

    @Test
    void rejectsEditThatChangesEvidenceReference() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        when(repository.findById(10L)).thenReturn(Optional.of(
                draft(10L, ReportDraftStatus.DRAFTED, token.digest(),
                        Instant.now().plusSeconds(60), report("摘要", "风险", "source-1"))));

        assertThatThrownBy(() -> service.edit(
                10L, token.rawToken(), report("摘要", "风险", "source-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能改变证据引用");
    }

    private ReportDraft draft(Long id, ReportDraftStatus status, String digest, Instant expiresAt) {
        return draft(id, status, digest, expiresAt, null);
    }

    private ReportDraft draft(Long id, ReportDraftStatus status, String digest, Instant expiresAt,
                              LlmReportOutput content) {
        return new ReportDraft(
                id, 20L, content, status, null, null, digest,
                "operator-1", null, expiresAt, Instant.now(), Instant.now());
    }

    private LlmReportOutput report(String summary, String risk, String sourceId) {
        return new LlmReportOutput(summary, List.of(sourceId), List.of(), List.of(),
                List.of(new ReportItem(risk, List.of(sourceId))),
                List.of(new ReportActionItem(ReportActionItemOrigin.SOURCE_ACTION,
                        "跟进行动", List.of(sourceId))),
                List.of(new ReportActionItem(ReportActionItemOrigin.AI_SUGGESTION,
                        "AI 建议", List.of())),
                List.of(new ReportCitation(sourceId, "支持报告")));
    }
}
