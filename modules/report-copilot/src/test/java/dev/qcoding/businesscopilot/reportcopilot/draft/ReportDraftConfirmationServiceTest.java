package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
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
    private ReportDraftConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new ReportDraftConfirmationService(
                repository, auditService,
                () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
                new DefaultObjectAccessPolicy(), tokenService);
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

    private ReportDraft draft(Long id, ReportDraftStatus status, String digest, Instant expiresAt) {
        return new ReportDraft(
                id, 20L, null, status, null, null, digest,
                "operator-1", null, expiresAt, Instant.now(), Instant.now());
    }
}
