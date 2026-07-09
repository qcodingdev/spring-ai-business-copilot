package dev.qcoding.businesscopilot.supportcopilot.draft;

import java.util.Optional;

/**
 * Repository for {@link SupportReplyDraft} persistence.
 *
 * <p>回复草稿仓库接口。定义草稿的创建、查询、确认和取消操作。</p>
 */
public interface SupportReplyDraftRepository {

    SupportReplyDraft save(SupportReplyDraft draft);

    Optional<SupportReplyDraft> findById(Long id);

    Optional<SupportReplyDraft> findByConfirmationToken(String token);

    boolean markConfirmed(Long id);

    boolean markCanceled(Long id);

    long count();
}
