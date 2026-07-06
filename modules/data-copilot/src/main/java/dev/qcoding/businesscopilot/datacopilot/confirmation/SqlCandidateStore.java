package dev.qcoding.businesscopilot.datacopilot.confirmation;

/**
 * Store for SQL candidates awaiting confirmation.
 *
 * <p>SQL 候选存储抽象。第一版使用 {@link InMemorySqlCandidateStore}，
 * 不引入 Redis，不做集群会话一致性。</p>
 */
public interface SqlCandidateStore {

    /** Persist a candidate by its candidateId. */
    void save(SqlCandidate candidate);

    /** Retrieve a candidate by candidateId, or {@code null} if not found. */
    SqlCandidate findById(String candidateId);

    /** Remove a candidate (e.g. after consumption). */
    void remove(String candidateId);

    /** Evict expired candidates from the store. */
    void evictExpired();
}
