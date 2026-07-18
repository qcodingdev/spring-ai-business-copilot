package dev.qcoding.businesscopilot.supportcopilot.ticket;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditRepository;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationRequest;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationResponse;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationService;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftRequest;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftResponse;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftService;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeQuery;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeResult;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeRetriever;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketAnalysisServiceTest {

    @Test
    void shouldNotGenerateDraftWhenKnowledgeEvidenceIsMissing() {
        var classificationService = new FixedClassificationService();
        var draftService = new CountingDraftService();
        var ticketRepository = new InMemoryTicketRepository();
        var auditRepository = new InMemoryAuditRepository();
        var service = new TicketAnalysisService(
                classificationService,
                query -> SupportKnowledgeResult.noResults("empty"),
                draftService,
                ticketRepository,
                new SupportAuditService(auditRepository),
                new SensitiveTextMasker(),
                new SupportCopilotProperties(true, 2000, 10,
                        "REFUND,ACCOUNT_SECURITY,INCIDENT", true, 5),
                actorProvider());

        var result = service.analyze(new TicketClassificationRequest("如何申请退款？", "web"));

        assertNull(result.draft().draftId());
        assertTrue(result.draft().needsHuman());
        assertEquals(0, draftService.calls);
        assertEquals(SupportTicketStatus.NEEDS_HUMAN, ticketRepository.lastStatus);
        assertEquals("NEEDS_HUMAN", auditRepository.saved.getLast().eventType());
    }

    private static class FixedClassificationService extends TicketClassificationService {
        FixedClassificationService() {
            super(null, null, new SensitiveTextMasker(),
                    new SupportCopilotProperties(true, 2000, 10,
                            "REFUND,ACCOUNT_SECURITY,INCIDENT", true, 5));
        }

        @Override
        public TicketClassificationResponse classify(TicketClassificationRequest request) {
            return new TicketClassificationResponse(
                    dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory.PRODUCT_USAGE,
                    dev.qcoding.businesscopilot.supportcopilot.classification.TicketSentiment.NEUTRAL,
                    dev.qcoding.businesscopilot.supportcopilot.classification.TicketUrgency.LOW,
                    "客户咨询产品使用问题",
                    false,
                    List.of());
        }

        @Override
        public ClassificationInvocation classifyWithMetadata(TicketClassificationRequest request) {
            return new ClassificationInvocation(classify(request), null, null);
        }

        @Override
        public String maskedMessage(String rawMessage) {
            return rawMessage;
        }
    }

    private static class CountingDraftService extends ReplyDraftService {
        private int calls;

        CountingDraftService() {
            super(null, null, new SensitiveTextMasker(), null, null,
                    new SupportCopilotProperties(true, 2000, 10,
                            "REFUND,ACCOUNT_SECURITY,INCIDENT", true, 5),
                    actorProvider(), new ConfirmationTokenService());
        }

        @Override
        public ReplyDraftResponse generate(ReplyDraftRequest request) {
            calls++;
            return new ReplyDraftResponse(1L, "草稿", "LOW", List.of(),
                    List.of(), "token", Instant.now().plusSeconds(60).toString(), false);
        }

        @Override
        public DraftInvocation generateWithMetadata(ReplyDraftRequest request) {
            return new DraftInvocation(generate(request), null, null);
        }
    }

    private static class InMemoryTicketRepository implements SupportTicketRepository {
        private SupportTicketStatus lastStatus;

        @Override
        public SupportTicket save(SupportTicket ticket) {
            return new SupportTicket(100L, ticket.externalId(), ticket.customerMessage(),
                    ticket.channel(), ticket.category(), ticket.sentiment(),
                    ticket.urgency(), ticket.status(), ticket.ownerActorId(),
                    Instant.now(), Instant.now());
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

    private static CurrentActorProvider actorProvider() {
        return () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR));
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
