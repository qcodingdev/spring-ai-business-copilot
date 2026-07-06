package dev.qcoding.businesscopilot.datacopilot.confirmation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link SqlCandidateStore}.
 *
 * <p>内存版 SQL 候选存储。使用 ConcurrentHashMap，适用于单实例部署。
 * 不引入 Redis，不做集群会话一致性。</p>
 */
public class InMemorySqlCandidateStore implements SqlCandidateStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySqlCandidateStore.class);

    private final ConcurrentHashMap<String, SqlCandidate> store = new ConcurrentHashMap<>();

    @Override
    public void save(SqlCandidate candidate) {
        store.put(candidate.candidateId(), candidate);
        log.debug("Saved SQL candidate: id={}, executable={}", candidate.candidateId(), candidate.executable());
    }

    @Override
    public SqlCandidate findById(String candidateId) {
        return store.get(candidateId);
    }

    @Override
    public void remove(String candidateId) {
        store.remove(candidateId);
        log.debug("Removed SQL candidate: id={}", candidateId);
    }

    @Override
    public void evictExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(entry -> {
            SqlCandidate candidate = entry.getValue();
            if (candidate.expiresAt() != null && now.isAfter(candidate.expiresAt())) {
                log.debug("Evicted expired SQL candidate: id={}", candidate.candidateId());
                return true;
            }
            return false;
        });
    }
}
