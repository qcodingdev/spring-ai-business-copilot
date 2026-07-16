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

    boolean transitionStatus(Long id, String expectedStatus, String targetStatus,
                             String actionActorId, Instant now);

    long count();
}
