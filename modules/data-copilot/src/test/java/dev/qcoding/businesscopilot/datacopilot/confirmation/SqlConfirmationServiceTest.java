package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlConfirmationServiceTest {

    private static final String SQL = "SELECT id, name FROM customers LIMIT 10";

    private final TestStore store = new TestStore();
    private final MutableActorProvider actors = new MutableActorProvider();
    private SqlConfirmationService service;

    @BeforeEach
    void setUp() {
        actors.actor = new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR));
        service = new SqlConfirmationService(
                store,
                new DataCopilotConfirmationProperties(10),
                actors,
                new DefaultObjectAccessPolicy(),
                new ConfirmationTokenService());
    }

    @Test
    void executableCandidatePersistsDigestButReturnsRawTokenOnce() {
        SqlCandidate created = service.createExecutableCandidate(SQL);
        SqlCandidate persisted = store.findById(created.candidateId());

        assertThat(created.confirmationToken()).isNotBlank();
        assertThat(created.ownerActorId()).isEqualTo("operator-1");
        assertThat(persisted.confirmationToken()).isNull();
        assertThat(persisted.tokenDigest()).hasSize(64);
        assertThat(persisted.tokenDigest()).doesNotContain(created.confirmationToken());
    }

    @Test
    void ownerCanConsumeOnlyOnce() {
        SqlCandidate created = service.createExecutableCandidate(SQL);

        SqlCandidate consumed = service.confirmAndConsume(
                created.candidateId(), created.confirmationToken());

        assertThat(consumed.status()).isEqualTo(SqlCandidateStatus.CONSUMED);
        assertThat(consumed.sql()).isEqualTo(SQL);
        assertThatThrownBy(() -> service.confirmAndConsume(
                created.candidateId(), created.confirmationToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    void anotherOperatorCannotUseCorrectToken() {
        SqlCandidate created = service.createExecutableCandidate(SQL);
        actors.actor = new CurrentActor("operator-2", Set.of(BusinessRole.OPERATOR));

        assertThatThrownBy(() -> service.confirmAndConsume(
                created.candidateId(), created.confirmationToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void adminCanConsumeAnotherActorsCandidate() {
        SqlCandidate created = service.createExecutableCandidate(SQL);
        actors.actor = new CurrentActor("admin", Set.of(BusinessRole.ADMIN));

        assertThat(service.confirmAndConsume(created.candidateId(), created.confirmationToken()).status())
                .isEqualTo(SqlCandidateStatus.CONSUMED);
    }

    @Test
    void wrongTokenIsRejectedWithoutConsumingCandidate() {
        SqlCandidate created = service.createExecutableCandidate(SQL);

        assertThatThrownBy(() -> service.confirmAndConsume(created.candidateId(), "wrong"))
                .isInstanceOf(SqlCandidateNotExecutableException.class);
        assertThat(store.findById(created.candidateId()).status())
                .isEqualTo(SqlCandidateStatus.PENDING);
    }

    @Test
    void expiredCandidateTransitionsToExpired() {
        SqlCandidate created = service.createExecutableCandidate(SQL);
        SqlCandidate persisted = store.findById(created.candidateId());
        store.rows.put(created.candidateId(), copyWithExpiry(
                persisted, Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> service.confirmAndConsume(
                created.candidateId(), created.confirmationToken()))
                .isInstanceOf(SqlCandidateExpiredException.class);
        assertThat(store.findById(created.candidateId()).status())
                .isEqualTo(SqlCandidateStatus.EXPIRED);
    }

    @Test
    void rejectedCandidateIsNotPersisted() {
        SqlCandidate candidate = service.createNotExecutableCandidate(SQL);
        assertThat(candidate.status()).isEqualTo(SqlCandidateStatus.REJECTED);
        assertThat(store.findById(candidate.candidateId())).isNull();
    }

    private SqlCandidate copyWithExpiry(SqlCandidate candidate, Instant expiresAt) {
        return new SqlCandidate(
                candidate.candidateId(), candidate.sql(), null, candidate.tokenDigest(),
                candidate.status(), candidate.ownerActorId(), candidate.requestId(),
                candidate.modelName(), candidate.promptName(), candidate.promptVersion(),
                candidate.promptHash(), candidate.aiMetadata(), candidate.policyVersion(),
                candidate.createdAt(), expiresAt,
                candidate.consumedAt(), candidate.actionActorId());
    }

    private static final class MutableActorProvider implements CurrentActorProvider {
        private CurrentActor actor;

        @Override
        public CurrentActor currentActor() {
            return actor;
        }
    }

    private static final class TestStore implements SqlCandidateStore {
        private final ConcurrentHashMap<String, SqlCandidate> rows = new ConcurrentHashMap<>();

        @Override
        public void save(SqlCandidate candidate) {
            rows.put(candidate.candidateId(), new SqlCandidate(
                    candidate.candidateId(), candidate.sql(), null, candidate.tokenDigest(),
                    candidate.status(), candidate.ownerActorId(), candidate.requestId(),
                    candidate.modelName(), candidate.promptName(), candidate.promptVersion(),
                    candidate.promptHash(), candidate.aiMetadata(), candidate.policyVersion(),
                    candidate.createdAt(), candidate.expiresAt(),
                    candidate.consumedAt(), candidate.actionActorId()));
        }

        @Override
        public SqlCandidate findById(String candidateId) {
            return rows.get(candidateId);
        }

        @Override
        public boolean consume(String candidateId, String actionActorId, Instant now) {
            return rows.computeIfPresent(candidateId, (id, current) -> {
                if (current.status() != SqlCandidateStatus.PENDING
                        || current.isExpired(now)) {
                    return current;
                }
                return new SqlCandidate(
                        current.candidateId(), current.sql(), null, null,
                        SqlCandidateStatus.CONSUMED, current.ownerActorId(),
                        current.requestId(), current.modelName(), current.promptName(),
                        current.promptVersion(), current.promptHash(),
                        current.aiMetadata(), current.policyVersion(), current.createdAt(),
                        current.expiresAt(), now, actionActorId);
            }).status() == SqlCandidateStatus.CONSUMED;
        }

        @Override
        public boolean expire(String candidateId, Instant now) {
            SqlCandidate current = rows.get(candidateId);
            if (current == null || current.status() != SqlCandidateStatus.PENDING) {
                return false;
            }
            rows.put(candidateId, new SqlCandidate(
                    current.candidateId(), current.sql(), null, null,
                    SqlCandidateStatus.EXPIRED, current.ownerActorId(), current.requestId(),
                    current.modelName(), current.promptName(), current.promptVersion(),
                    current.promptHash(), current.aiMetadata(), current.policyVersion(),
                    current.createdAt(), current.expiresAt(), null, null));
            return true;
        }

        @Override
        public int evictExpired(Instant now) {
            int before = (int) rows.values().stream()
                    .filter(row -> row.status() == SqlCandidateStatus.PENDING && row.isExpired(now))
                    .count();
            rows.values().stream()
                    .filter(row -> row.status() == SqlCandidateStatus.PENDING && row.isExpired(now))
                    .map(SqlCandidate::candidateId)
                    .toList()
                    .forEach(id -> expire(id, now));
            return before;
        }
    }
}
