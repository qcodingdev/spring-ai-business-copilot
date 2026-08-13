package dev.qcoding.businesscopilot.supportcopilot.draft;

import java.util.Optional;
import java.time.Instant;

/**
 * Repository for {@link SupportReplyDraft} persistence.
 *
 * <p>回复草稿仓库接口。定义草稿的创建、查询、确认和取消操作。</p>
 */
public interface SupportReplyDraftRepository {

    SupportReplyDraft save(SupportReplyDraft draft);

    Optional<SupportReplyDraft> findById(Long id);

    boolean transitionStatus(Long id, SupportDraftStatus expectedStatus, SupportDraftStatus targetStatus,
                             SupportDecisionOutcome outcome, String actionActorId, Instant now);

    boolean edit(Long id, SupportDraftStatus expectedStatus, String editedText,
                 String editReason, String editedByActorId, Instant now);

    boolean replaceConfirmationToken(Long id, SupportDraftStatus expectedStatus,
                                     String expectedReviewerActorId, String tokenDigest,
                                     String reviewerActorId, Instant expiresAt, Instant now);

    long count();
}
