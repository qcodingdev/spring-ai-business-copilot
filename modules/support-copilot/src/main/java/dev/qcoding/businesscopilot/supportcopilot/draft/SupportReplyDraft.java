package dev.qcoding.businesscopilot.supportcopilot.draft;

import java.time.Instant;

/** Persisted support draft plus a creation-only raw confirmation token. */
public record SupportReplyDraft(
        Long id,
        Long ticketId,
        String draftText,
        String citedChunkIds,
        String riskLevel,
        String riskReasons,
        String confirmationToken,
        String tokenDigest,
        String status,
        String ownerActorId,
        boolean reviewQueue,
        String reviewerActorId,
        String actionActorId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
