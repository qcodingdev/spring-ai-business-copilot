package dev.qcoding.businesscopilot.reportcopilot.draft;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDraftConfirmationServiceTest {

    private final ReportDraftRepository repository = mock(ReportDraftRepository.class);
    private final ReportAuditService auditService = mock(ReportAuditService.class);
    private final ReportDraftConfirmationService service = new ReportDraftConfirmationService(repository, auditService);

    @Test
    void confirmsOnlyTheDraftBoundToAnUnexpiredToken() {
        when(repository.findByConfirmationToken("token-1")).thenReturn(Optional.of(draft(10L, Instant.now().plusSeconds(60))));
        when(repository.transitionStatus(10L, ReportDraftStatus.DRAFTED, ReportDraftStatus.CONFIRMED)).thenReturn(true);

        var result = service.confirm(10L, "token-1");

        assertThat(result.status()).isEqualTo(ReportDraftStatus.CONFIRMED);
        verify(repository).transitionStatus(10L, ReportDraftStatus.DRAFTED, ReportDraftStatus.CONFIRMED);
        verify(auditService).record(any());
    }

    @Test
    void rejectsExpiredConfirmationToken() {
        when(repository.findByConfirmationToken("expired")).thenReturn(Optional.of(draft(10L, Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> service.confirm(10L, "expired"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    private ReportDraft draft(Long id, Instant expiresAt) {
        return new ReportDraft(id, 20L, null, ReportDraftStatus.DRAFTED, null,
                "token", expiresAt, Instant.now(), Instant.now());
    }
}
