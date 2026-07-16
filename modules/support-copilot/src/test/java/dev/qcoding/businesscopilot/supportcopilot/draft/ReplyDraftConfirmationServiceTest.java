package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditRepository;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicket;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplyDraftConfirmationServiceTest {

    private final ConfirmationTokenService tokenService = new ConfirmationTokenService();
    private final MutableActorProvider actors = new MutableActorProvider();
    private InMemoryDraftRepository draftRepository;
    private InMemoryTicketRepository ticketRepository;
    private InMemoryAuditRepository auditRepository;
    private ReplyDraftConfirmationService service;

    @BeforeEach
    void setUp() {
        actors.actor = new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR));
        draftRepository = new InMemoryDraftRepository();
        ticketRepository = new InMemoryTicketRepository();
        auditRepository = new InMemoryAuditRepository();
        service = new ReplyDraftConfirmationService(
                draftRepository,
                ticketRepository,
                new SupportAuditService(auditRepository),
                actors,
                new DefaultObjectAccessPolicy(),
                tokenService);
    }

    @Test
    void ownerConfirmsNormalDraftOnlyOnce() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().plusSeconds(60));

        var result = service.confirm(10L, token.rawToken());

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.ticketId()).isEqualTo(100L);
        assertThat(draftRepository.draft.status()).isEqualTo("CONFIRMED");
        assertThat(draftRepository.draft.tokenDigest()).isNull();
        assertThat(ticketRepository.lastStatus).isEqualTo("CONFIRMED");
        assertThat(auditRepository.saved.getFirst().eventType()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> service.confirm(10L, token.rawToken()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ownerCanCancelDraft() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().plusSeconds(60));

        var result = service.cancel(10L, token.rawToken());

        assertThat(result.status()).isEqualTo("CANCELED");
        assertThat(draftRepository.draft.actionActorId()).isEqualTo("operator-1");
        assertThat(ticketRepository.lastStatus).isEqualTo("CANCELED");
    }

    @Test
    void expiredTokenIsRejectedWithoutTransition() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.confirm(10L, token.rawToken()))
                .isInstanceOf(BusinessException.class);
        assertThat(draftRepository.draft.status()).isEqualTo("DRAFTED");
    }

    @Test
    void anotherOperatorCannotUseCorrectToken() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().plusSeconds(60));
        actors.actor = new CurrentActor("operator-2", Set.of(BusinessRole.OPERATOR));

        assertThatThrownBy(() -> service.confirm(10L, token.rawToken()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reviewerCanConfirmAssignedReviewQueueDraft() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, true, "reviewer-1", Instant.now().plusSeconds(60));
        actors.actor = new CurrentActor("reviewer-1", Set.of(BusinessRole.REVIEWER));

        assertThat(service.confirm(10L, token.rawToken()).status()).isEqualTo("CONFIRMED");
        assertThat(draftRepository.draft.actionActorId()).isEqualTo("reviewer-1");
    }

    private SupportReplyDraft draft(ConfirmationTokenService.IssuedToken token, boolean reviewQueue,
                                    String reviewerActorId, Instant expiresAt) {
        return new SupportReplyDraft(
                10L, 100L, "回复草稿", "chunk-1", "MEDIUM", "needs review",
                null, token.digest(), reviewQueue ? "NEEDS_REVIEW" : "DRAFTED",
                "operator-1", reviewQueue, reviewerActorId, null,
                expiresAt, Instant.now(), Instant.now());
    }

    private static final class MutableActorProvider implements CurrentActorProvider {
        private CurrentActor actor;

        @Override
        public CurrentActor currentActor() {
            return actor;
        }
    }

    private static final class InMemoryDraftRepository implements SupportReplyDraftRepository {
        private SupportReplyDraft draft;

        @Override
        public SupportReplyDraft save(SupportReplyDraft candidate) {
            draft = candidate;
            return candidate;
        }

        @Override
        public Optional<SupportReplyDraft> findById(Long id) {
            return draft != null && draft.id().equals(id) ? Optional.of(draft) : Optional.empty();
        }

        @Override
        public boolean transitionStatus(Long id, String expectedStatus, String targetStatus,
                                        String actionActorId, Instant now) {
            if (draft == null || !draft.id().equals(id) || !draft.status().equals(expectedStatus)
                    || draft.expiresAt() == null || !draft.expiresAt().isAfter(now)) {
                return false;
            }
            draft = new SupportReplyDraft(
                    draft.id(), draft.ticketId(), draft.draftText(), draft.citedChunkIds(),
                    draft.riskLevel(), draft.riskReasons(), null, null, targetStatus,
                    draft.ownerActorId(), draft.reviewQueue(), draft.reviewerActorId(),
                    actionActorId, draft.expiresAt(), draft.createdAt(), now);
            return true;
        }

        @Override
        public long count() {
            return draft == null ? 0 : 1;
        }
    }

    private static final class InMemoryTicketRepository implements SupportTicketRepository {
        private String lastStatus;

        @Override
        public SupportTicket save(SupportTicket ticket) {
            return ticket;
        }

        @Override
        public Optional<SupportTicket> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<SupportTicket> findRecent(int limit) {
            return List.of();
        }

        @Override
        public boolean transitionStatus(Long id, String expectedStatus, String targetStatus) {
            lastStatus = targetStatus;
            return true;
        }

        @Override
        public long count() {
            return 0;
        }
    }

    private static final class InMemoryAuditRepository implements SupportAuditRepository {
        private final List<SupportAuditLog> saved = new ArrayList<>();

        @Override
        public SupportAuditLog save(SupportAuditLog log) {
            saved.add(log);
            return log;
        }

        @Override
        public List<SupportAuditLog> findRecent(int page, int size) {
            return saved;
        }

        @Override
        public long count() {
            return saved.size();
        }
    }
}
