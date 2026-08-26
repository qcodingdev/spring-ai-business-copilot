package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import java.time.Instant;
import java.util.Optional;

/** Persistence boundary for durable knowledge indexing work. */
public interface KnowledgeIndexJobRepository {

    KnowledgeIndexJob enqueue(Long documentId);

    Optional<KnowledgeIndexJob> findById(Long id);

    Optional<KnowledgeIndexJob> findActiveByDocumentId(Long documentId);

    Optional<KnowledgeIndexJob> claimNext(Instant now);

    /** 锁定且仅锁定仍由当前 worker 持有的 PROCESSING 任务。 */
    boolean lockProcessing(Long id);

    boolean complete(Long id, String model, int dimension, int chunkCount, Instant now);

    boolean retry(Long id, String errorCategory, Instant nextAttemptAt, Instant now);

    boolean fail(Long id, String errorCategory, Instant now);

    boolean cancelStaleProcessing(Long id, Instant staleBefore, Instant now);
}
