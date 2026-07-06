package dev.qcoding.businesscopilot.datacopilot.confirmation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlConfirmationServiceTest {

    private static final String SQL = "SELECT id, name FROM customers LIMIT 10";

    private InMemorySqlCandidateStore store;
    private DataCopilotConfirmationProperties properties;
    private SqlConfirmationService service;

    @BeforeEach
    void setUp() {
        // 10-minute TTL, matching the v1 default
        properties = new DataCopilotConfirmationProperties(10);
        store = new InMemorySqlCandidateStore();
        service = new SqlConfirmationService(store, properties);
    }

    @Test
    @DisplayName("executable candidate generates secure confirmation token")
    void executableCandidateGeneratesToken() {
        SqlCandidate candidate = service.createExecutableCandidate(SQL);

        assertThat(candidate.executable()).isTrue();
        assertThat(candidate.confirmationToken()).isNotNull();
        assertThat(candidate.candidateId()).isNotNull();
        assertThat(candidate.expiresAt()).isNotNull();
        // token 不为空且与 candidateId 不同（不能用 requestId 代替）
        assertThat(candidate.confirmationToken()).isNotEqualTo(candidate.candidateId());
        assertThat(candidate.confirmationToken()).isNotBlank();
        assertThat(candidate.sql()).isEqualTo(SQL);
    }

    @Test
    @DisplayName("valid token retrieves candidate")
    void validTokenRetrievesCandidate() {
        SqlCandidate saved = service.createExecutableCandidate(SQL);

        SqlCandidate retrieved = service.confirmAndRetrieve(saved.candidateId(), saved.confirmationToken());

        assertThat(retrieved.sql()).isEqualTo(SQL);
        assertThat(retrieved.candidateId()).isEqualTo(saved.candidateId());
    }

    @Test
    @DisplayName("invalid token rejected")
    void invalidTokenRejected() {
        SqlCandidate saved = service.createExecutableCandidate(SQL);

        assertThatThrownBy(() -> service.confirmAndRetrieve(saved.candidateId(), "wrong-token"))
                .isInstanceOf(SqlCandidateNotExecutableException.class)
                .hasMessageContaining("Invalid confirmation token");
    }

    @Test
    @DisplayName("non-existent candidate rejected")
    void nonExistentCandidateRejected() {
        assertThatThrownBy(() -> service.confirmAndRetrieve("unknown-id", "any-token"))
                .isInstanceOf(SqlCandidateNotExecutableException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("null token rejected")
    void nullTokenRejected() {
        SqlCandidate saved = service.createExecutableCandidate(SQL);

        assertThatThrownBy(() -> service.confirmAndRetrieve(saved.candidateId(), null))
                .isInstanceOf(SqlCandidateNotExecutableException.class)
                .hasMessageContaining("Invalid confirmation token");
    }

    @Test
    @DisplayName("expired candidate rejected")
    void expiredCandidateRejected() {
        // 用 0 分钟 TTL 模拟立即过期（compact 约束保证至少 1 分钟，所以手动构造过期候选）
        SqlCandidate saved = service.createExecutableCandidate(SQL);
        // 手动塞入一个已过期的候选
        java.time.Instant past = java.time.Instant.now().minusSeconds(60);
        SqlCandidate expired = new SqlCandidate(
                saved.candidateId(), saved.sql(), saved.confirmationToken(),
                past.minusSeconds(60), past, true, null, null, null);
        store.save(expired);

        assertThatThrownBy(() -> service.confirmAndRetrieve(expired.candidateId(), expired.confirmationToken()))
                .isInstanceOf(SqlCandidateExpiredException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("guardrails-failed candidate does not generate token")
    void guardrailsFailedCandidateHasNoToken() {
        SqlCandidate candidate = service.createNotExecutableCandidate(SQL);

        assertThat(candidate.executable()).isFalse();
        assertThat(candidate.confirmationToken()).isNull();
        assertThat(candidate.expiresAt()).isNull();
    }

    @Test
    @DisplayName("non-executable candidate cannot be executed even with id")
    void nonExecutableCandidateRejectedOnConfirm() {
        SqlCandidate candidate = service.createNotExecutableCandidate(SQL);

        // 非 executable 候选没有 token，确认时会因 token 不匹配被拒绝
        assertThatThrownBy(() -> service.confirmAndRetrieve(candidate.candidateId(), null))
                .isInstanceOf(SqlCandidateNotExecutableException.class);
    }

    @Test
    @DisplayName("two candidates have distinct tokens and ids")
    void twoCandidatesAreDistinct() {
        SqlCandidate a = service.createExecutableCandidate(SQL);
        SqlCandidate b = service.createExecutableCandidate(SQL);

        assertThat(a.candidateId()).isNotEqualTo(b.candidateId());
        assertThat(a.confirmationToken()).isNotEqualTo(b.confirmationToken());
    }

    @Test
    @DisplayName("store evicts expired candidates")
    void storeEvictsExpired() {
        // 手动构造并保存一个已过期候选
        java.time.Instant past = java.time.Instant.now().minusSeconds(120);
        SqlCandidate expired = new SqlCandidate(
                "expired-id", SQL, "expired-token", past.minusSeconds(60), past, true, null, null, null);
        store.save(expired);
        assertThat(store.findById("expired-id")).isNotNull();

        store.evictExpired();
        assertThat(store.findById("expired-id")).isNull();
    }

    @Test
    @DisplayName("default TTL is 10 minutes when configured with non-positive value")
    void defaultTtlIsTenMinutes() {
        DataCopilotConfirmationProperties zero = new DataCopilotConfirmationProperties(0);
        DataCopilotConfirmationProperties negative = new DataCopilotConfirmationProperties(-5);

        assertThat(zero.candidateTtlMinutes()).isEqualTo(10);
        assertThat(negative.candidateTtlMinutes()).isEqualTo(10);
    }
}
