package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import java.time.Instant;

/** Persisted asynchronous indexing job with bounded retry metadata. */
public record KnowledgeIndexJob(
        Long id,
        Long documentId,
        KnowledgeIndexJobStatus status,
        int attempts,
        String embeddingModel,
        Integer embeddingDimension,
        Integer chunkCount,
        String errorCategory,
        Instant nextAttemptAt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {
}
