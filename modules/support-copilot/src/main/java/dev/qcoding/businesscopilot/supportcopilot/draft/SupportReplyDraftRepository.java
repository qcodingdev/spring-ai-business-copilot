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

    /** Atomically consume an unexpired token for the requested draft. */
    Optional<SupportReplyDraft> consumeConfirmationToken(Long id, String token, Instant now);

    long count();
}
