package dev.qcoding.businesscopilot.supportcopilot.draft;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyDraftConfirmationServiceTest {

    private InMemoryDraftRepository draftRepository;
    private InMemoryTicketRepository ticketRepository;
    private InMemoryAuditRepository auditRepository;
    private ReplyDraftConfirmationService service;

    @BeforeEach
    void setUp() {
        draftRepository = new InMemoryDraftRepository();
        ticketRepository = new InMemoryTicketRepository();
        auditRepository = new InMemoryAuditRepository();
        service = new ReplyDraftConfirmationService(
                draftRepository,
                ticketRepository,
                new SupportAuditService(auditRepository));
    }

    @Test
    void confirmShouldClearTokenUpdateTicketAndWriteAudit() {
        draftRepository.draft = draft("token-1", Instant.now().plusSeconds(60));

        var result = service.confirm(10L, "token-1");

        assertEquals("CONFIRMED", result.status());
        assertEquals(100L, result.ticketId());
        assertTrue(draftRepository.confirmed);
        assertEquals("CONFIRMED", ticketRepository.lastStatus);
        assertEquals("CONFIRMED", auditRepository.saved.getFirst().eventType());
        assertEquals(100L, auditRepository.saved.getFirst().ticketId());
    }

    @Test
    void cancelShouldClearTokenUpdateTicketAndWriteAudit() {
        draftRepository.draft = draft("token-1", Instant.now().plusSeconds(60));

        var result = service.cancel(10L, "token-1");

        assertEquals("CANCELED", result.status());
        assertTrue(draftRepository.canceled);
        assertEquals("CANCELED", ticketRepository.lastStatus);
        assertEquals("CANCELED", auditRepository.saved.getFirst().eventType());
        assertEquals(100L, auditRepository.saved.getFirst().ticketId());
    }

    @Test
    void confirmShouldRejectExpiredToken() {
        draftRepository.draft = draft("token-1", Instant.now().minusSeconds(1));

        assertThrows(BusinessException.class, () -> service.confirm(10L, "token-1"));
    }

    @Test
    void confirmShouldRejectMismatchedDraftId() {
        draftRepository.draft = draft("token-1", Instant.now().plusSeconds(60));

        assertThrows(BusinessException.class, () -> service.confirm(99L, "token-1"));
    }

    private static SupportReplyDraft draft(String token, Instant expiresAt) {
        return new SupportReplyDraft(
                10L,
                100L,
                "回复草稿",
                "chunk-1",
                "MEDIUM",
                "needs review",
                token,
                expiresAt,
                Instant.now());
    }

    private static class InMemoryDraftRepository implements SupportReplyDraftRepository {
        private SupportReplyDraft draft;
        private boolean confirmed;
        private boolean canceled;

        @Override
        public SupportReplyDraft save(SupportReplyDraft draft) {
            this.draft = draft;
            return draft;
        }

        @Override
        public Optional<SupportReplyDraft> findById(Long id) {
            return draft != null && draft.id().equals(id) ? Optional.of(draft) : Optional.empty();
        }

        @Override
        public Optional<SupportReplyDraft> findByConfirmationToken(String token) {
            return draft != null && token.equals(draft.confirmationToken()) ? Optional.of(draft) : Optional.empty();
        }

        @Override
        public boolean markConfirmed(Long id) {
            confirmed = true;
            return true;
        }

        @Override
        public boolean markCanceled(Long id) {
            canceled = true;
            return true;
        }

        @Override
        public long count() {
            return draft == null ? 0 : 1;
        }
    }

    private static class InMemoryTicketRepository implements SupportTicketRepository {
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
        public boolean updateStatus(Long id, String status) {
            lastStatus = status;
            return true;
        }

        @Override
        public long count() {
            return 0;
        }
    }

    private static class InMemoryAuditRepository implements SupportAuditRepository {
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
