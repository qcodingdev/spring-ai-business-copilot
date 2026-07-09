package dev.qcoding.businesscopilot.supportcopilot.draft;

import java.time.Instant;

/**
 * Immutable domain model for a support reply draft.
 *
 * <p>客服回复草稿。draftText 入库前必须脱敏。confirmationToken 由服务端生成，
 * expiresAt 控制确认有效期。</p>
 *
 * @param id                primary key
 * @param ticketId          associated ticket ID
 * @param draftText         masked reply draft text
 * @param citedChunkIds     comma-separated knowledge chunk IDs used as evidence
 * @param riskLevel         LOW, MEDIUM, HIGH
 * @param riskReasons       comma-separated risk reasons
 * @param confirmationToken server-generated confirmation token
 * @param expiresAt         token expiry timestamp
 * @param createdAt         creation timestamp
 */
public record SupportReplyDraft(
        Long id,
        Long ticketId,
        String draftText,
        String citedChunkIds,
        String riskLevel,
        String riskReasons,
        String confirmationToken,
        Instant expiresAt,
        Instant createdAt) {
}
