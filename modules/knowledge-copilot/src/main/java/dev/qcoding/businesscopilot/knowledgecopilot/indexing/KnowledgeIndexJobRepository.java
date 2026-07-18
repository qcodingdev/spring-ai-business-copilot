package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import java.time.Instant;
import java.util.Optional;

/** Persistence boundary for durable knowledge indexing work. */
public interface KnowledgeIndexJobRepository {

    KnowledgeIndexJob enqueue(Long documentId);

    Optional<KnowledgeIndexJob> findById(Long id);

    Optional<KnowledgeIndexJob> claimNext(Instant now);

    void complete(Long id, String model, int dimension, int chunkCount, Instant now);

    void retry(Long id, String errorCategory, Instant nextAttemptAt, Instant now);

    void fail(Long id, String errorCategory, Instant now);
}
