package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditRepository;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicket;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository;
import dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketStatus;
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
                tokenService,
                new SensitiveTextMasker(),
                new SupportCopilotProperties(true, 2000, 10, null, true, 5));
    }

    @Test
    void ownerConfirmsNormalDraftOnlyOnce() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().plusSeconds(60));

        var result = service.confirm(10L, token.rawToken());

        assertThat(result.status()).isEqualTo(SupportDraftStatus.CONFIRMED);
        assertThat(result.ticketId()).isEqualTo(100L);
        assertThat(draftRepository.draft.status()).isEqualTo(SupportDraftStatus.CONFIRMED);
        assertThat(draftRepository.draft.tokenDigest()).isNull();
        assertThat(ticketRepository.lastStatus).isEqualTo(SupportTicketStatus.CONFIRMED);
        assertThat(auditRepository.saved.getFirst().eventType()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> service.confirm(10L, token.rawToken()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ownerCanCancelDraft() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().plusSeconds(60));

        var result = service.cancel(10L, token.rawToken());

        assertThat(result.status()).isEqualTo(SupportDraftStatus.CANCELED);
        assertThat(draftRepository.draft.actionActorId()).isEqualTo("operator-1");
        assertThat(ticketRepository.lastStatus).isEqualTo(SupportTicketStatus.CANCELED);
    }

    @Test
    void expiredTokenIsRejectedWithoutTransition() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.confirm(10L, token.rawToken()))
                .isInstanceOf(BusinessException.class);
        assertThat(draftRepository.draft.status()).isEqualTo(SupportDraftStatus.DRAFTED);
    }

    @Test
    void expiredPendingDraftCanOpenFreshSessionWithoutRevivingOldToken() {
        ConfirmationTokenService.IssuedToken oldToken = tokenService.issue();
        draftRepository.draft = draft(oldToken, false, null, Instant.now().minusSeconds(1));

        var session = service.openReviewSession(10L);

        assertThat(session.expiresAt()).isAfter(Instant.now().plusSeconds(500));
        assertThat(tokenService.matches(oldToken.rawToken(), draftRepository.draft.tokenDigest())).isFalse();
        assertThat(service.confirm(10L, session.confirmationToken()).status())
                .isEqualTo(SupportDraftStatus.CONFIRMED);
        assertThat(auditRepository.saved.getFirst().eventType()).isEqualTo("REVIEW_SESSION_OPENED");
    }

    @Test
    void assignedReviewerCannotBeOverwrittenByAnotherReviewer() {
        ConfirmationTokenService.IssuedToken oldToken = tokenService.issue();
        draftRepository.draft = draft(oldToken, true, null, Instant.now().minusSeconds(1));
        actors.actor = new CurrentActor("reviewer-1", Set.of(BusinessRole.REVIEWER));

        service.openReviewSession(10L);
        actors.actor = new CurrentActor("reviewer-2", Set.of(BusinessRole.REVIEWER));

        assertThatThrownBy(() -> service.openReviewSession(10L))
                .isInstanceOf(BusinessException.class);
        assertThat(draftRepository.draft.reviewerActorId()).isEqualTo("reviewer-1");
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

        assertThat(service.confirm(10L, token.rawToken()).status())
                .isEqualTo(SupportDraftStatus.CONFIRMED);
        assertThat(draftRepository.draft.actionActorId()).isEqualTo("reviewer-1");
    }

    @Test
    void ownerCanEditDraftAndSensitiveTextIsMaskedBeforeConfirmation() {
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        draftRepository.draft = draft(token, false, null, Instant.now().plusSeconds(60));

        var edit = service.edit(10L, "请联系 alex@example.com，我们将说明流程。", "人工修订");
        var confirmation = service.confirm(10L, token.rawToken());

        assertThat(edit.editedText()).contains("a***@example.com");
        assertThat(draftRepository.draft.editedDraftText()).contains("a***@example.com");
        assertThat(draftRepository.draft.decisionOutcome()).isEqualTo(SupportDecisionOutcome.EDITED_ACCEPTED);
        assertThat(confirmation.status()).isEqualTo(SupportDraftStatus.CONFIRMED);
    }

    private SupportReplyDraft draft(ConfirmationTokenService.IssuedToken token, boolean reviewQueue,
                                    String reviewerActorId, Instant expiresAt) {
        return new SupportReplyDraft(
                10L, 100L, "回复草稿", "chunk-1", SupportRiskLevel.MEDIUM, "needs review",
                null, token.digest(), reviewQueue
                        ? SupportDraftStatus.NEEDS_REVIEW : SupportDraftStatus.DRAFTED,
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
        public boolean transitionStatus(Long id, SupportDraftStatus expectedStatus,
                                        SupportDraftStatus targetStatus,
                                        SupportDecisionOutcome outcome,
                                        String actionActorId, Instant now) {
            if (draft == null || !draft.id().equals(id) || !draft.status().equals(expectedStatus)
                    || draft.expiresAt() == null || !draft.expiresAt().isAfter(now)) {
                return false;
            }
            draft = new SupportReplyDraft(
                    draft.id(), draft.ticketId(), draft.draftText(), draft.citedChunkIds(),
                    draft.knowledgeVersionIds(), draft.riskLevel(), draft.riskReasons(), null, null, targetStatus,
                    draft.ownerActorId(), draft.reviewQueue(), draft.reviewerActorId(),
                    actionActorId, draft.originalDraftText(), draft.editedDraftText(),
                    draft.editReason(), draft.editedByActorId(), draft.editedAt(), outcome,
                    draft.expiresAt(), draft.createdAt(), now);
            return true;
        }

        @Override
        public boolean edit(Long id, SupportDraftStatus expectedStatus, String editedText,
                            String editReason, String editedByActorId, Instant now) {
            if (draft == null || !draft.id().equals(id) || draft.status() != expectedStatus) {
                return false;
            }
            draft = new SupportReplyDraft(
                    draft.id(), draft.ticketId(), editedText, draft.citedChunkIds(),
                    draft.knowledgeVersionIds(), draft.riskLevel(), draft.riskReasons(),
                    null, draft.tokenDigest(), draft.status(), draft.ownerActorId(),
                    draft.reviewQueue(), draft.reviewerActorId(), draft.actionActorId(),
                    draft.originalDraftText(), editedText, editReason, editedByActorId,
                    now, draft.decisionOutcome(), draft.expiresAt(), draft.createdAt(), now);
            return true;
        }

        @Override
        public boolean replaceConfirmationToken(Long id, SupportDraftStatus expectedStatus,
                                                String expectedReviewerActorId, String tokenDigest,
                                                String reviewerActorId, Instant expiresAt, Instant now) {
            if (draft == null || !draft.id().equals(id) || draft.status() != expectedStatus
                    || !java.util.Objects.equals(
                            draft.reviewerActorId(), expectedReviewerActorId)) {
                return false;
            }
            draft = new SupportReplyDraft(
                    draft.id(), draft.ticketId(), draft.draftText(), draft.citedChunkIds(),
                    draft.knowledgeVersionIds(), draft.riskLevel(), draft.riskReasons(),
                    null, tokenDigest, draft.status(), draft.ownerActorId(), draft.reviewQueue(),
                    reviewerActorId, draft.actionActorId(), draft.originalDraftText(),
                    draft.editedDraftText(), draft.editReason(), draft.editedByActorId(), draft.editedAt(),
                    draft.decisionOutcome(), expiresAt, draft.createdAt(), now);
            return true;
        }

        @Override
        public long count() {
            return draft == null ? 0 : 1;
        }
    }

    private static final class InMemoryTicketRepository implements SupportTicketRepository {
        private SupportTicketStatus lastStatus;

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
        public boolean transitionStatus(Long id, SupportTicketStatus expectedStatus,
                                        SupportTicketStatus targetStatus) {
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
