package dev.qcoding.businesscopilot.datacopilot.confirmation;

import java.time.Instant;

/** Persistence boundary for database-backed SQL confirmation candidates. */
public interface SqlCandidateStore {

    void save(SqlCandidate candidate);

    SqlCandidate findById(String candidateId);

    boolean consume(String candidateId, String actionActorId, Instant now);

    boolean expire(String candidateId, Instant now);

    int evictExpired(Instant now);
}
