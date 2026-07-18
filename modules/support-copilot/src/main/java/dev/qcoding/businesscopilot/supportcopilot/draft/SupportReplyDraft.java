package dev.qcoding.businesscopilot.supportcopilot.draft;

import java.time.Instant;

/** Persisted support draft plus a creation-only raw confirmation token. */
public record SupportReplyDraft(
        Long id,
        Long ticketId,
        String draftText,
        String citedChunkIds,
        String knowledgeVersionIds,
        dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel riskLevel,
        String riskReasons,
        String confirmationToken,
        String tokenDigest,
        SupportDraftStatus status,
        String ownerActorId,
        boolean reviewQueue,
        String reviewerActorId,
        String actionActorId,
        String originalDraftText,
        String editedDraftText,
        String editReason,
        String editedByActorId,
        Instant editedAt,
        SupportDecisionOutcome decisionOutcome,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public SupportReplyDraft(
            Long id, Long ticketId, String draftText, String citedChunkIds,
            dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel riskLevel,
            String riskReasons, String confirmationToken, String tokenDigest,
            SupportDraftStatus status, String ownerActorId, boolean reviewQueue,
            String reviewerActorId, String actionActorId, Instant expiresAt,
            Instant createdAt, Instant updatedAt) {
        this(id, ticketId, draftText, citedChunkIds, null, riskLevel, riskReasons,
                confirmationToken, tokenDigest, status, ownerActorId, reviewQueue,
                reviewerActorId, actionActorId, draftText, null, null, null, null,
                SupportDecisionOutcome.PENDING, expiresAt, createdAt, updatedAt);
    }
}
