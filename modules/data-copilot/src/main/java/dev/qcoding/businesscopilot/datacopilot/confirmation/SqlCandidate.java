package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;

import java.time.Instant;

/** Persisted SQL candidate plus a creation-only raw confirmation token. */
public record SqlCandidate(
        String candidateId,
        String sql,
        String confirmationToken,
        String tokenDigest,
        SqlCandidateStatus status,
        String ownerActorId,
        String requestId,
        String modelName,
        String promptName,
        String promptVersion,
        String promptHash,
        AiInvocationMetadata aiMetadata,
        String policyVersion,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt,
        String actionActorId) {

    public boolean executable() {
        return status == SqlCandidateStatus.PENDING && tokenDigest != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
