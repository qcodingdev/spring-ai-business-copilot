package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.PreparedKnowledgeIndex;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 索引任务租约与知识文档状态的事务边界。
 *
 * <p>耗时的模型调用不进入数据库事务。提交阶段先锁定仍为 PROCESSING 的任务，
 * 再在同一事务内替换向量、完成任务并启用文档。被恢复流程取消的旧 worker
 * 无法再删除新向量，也无法覆盖替代任务写入的生命周期状态。</p>
 */
public class KnowledgeIndexLifecycleService {

    private final KnowledgeIndexJobRepository jobRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeEmbeddingService embeddingService;

    public KnowledgeIndexLifecycleService(KnowledgeIndexJobRepository jobRepository,
                                          KnowledgeDocumentRepository documentRepository,
                                          KnowledgeEmbeddingService embeddingService) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.embeddingService = embeddingService;
    }

    /** 领取一个任务，并与文档 PROCESSING 状态一并提交。 */
    @Transactional
    public Optional<KnowledgeIndexJob> claimNext(Instant now) {
        Optional<KnowledgeIndexJob> claimed = jobRepository.claimNext(now);
        claimed.ifPresent(job -> requireDocumentState(
                documentRepository.updateIndexStatus(job.documentId(), "PROCESSING", null, false),
                job.documentId(), "PROCESSING"));
        return claimed;
    }

    /** 在租约仍有效时原子替换向量，并完成任务和文档状态。 */
    @Transactional
    public boolean completeWithEmbeddings(KnowledgeIndexJob job,
                                          PreparedKnowledgeIndex prepared,
                                          Instant now) {
        if (!prepared.result().documentId().equals(job.documentId())) {
            throw new IllegalArgumentException("待提交向量与索引任务不属于同一文档");
        }
        if (!jobRepository.lockProcessing(job.id())) {
            return false;
        }

        EmbeddingIndexResult result = embeddingService.persistPreparedIndex(prepared);
        requireJobTransition(jobRepository.complete(
                job.id(), result.modelName(), result.dimension(), result.chunkCount(), now), job.id());
        requireDocumentState(documentRepository.updateIndexStatus(
                job.documentId(), "INDEXED", null, result.chunkCount() > 0),
                job.documentId(), "INDEXED");
        return true;
    }

    /** Embedding 未启用时，原子完成文本检索降级状态。 */
    @Transactional
    public boolean completeTextSearchOnly(KnowledgeIndexJob job, int chunkCount, Instant now) {
        if (!jobRepository.lockProcessing(job.id())) {
            return false;
        }
        requireJobTransition(jobRepository.complete(
                job.id(), "text-search-only", 0, chunkCount, now), job.id());
        requireDocumentState(documentRepository.updateIndexStatus(
                job.documentId(), "INDEXED", "TEXT_SEARCH_ONLY", chunkCount > 0),
                job.documentId(), "INDEXED");
        return true;
    }

    /** 在租约仍有效时原子安排重试并同步文档状态。 */
    @Transactional
    public boolean retry(KnowledgeIndexJob job,
                         String errorCategory,
                         Instant nextAttemptAt,
                         Instant now) {
        if (!jobRepository.lockProcessing(job.id())) {
            return false;
        }
        requireJobTransition(jobRepository.retry(
                job.id(), errorCategory, nextAttemptAt, now), job.id());
        requireDocumentState(documentRepository.updateIndexStatus(
                job.documentId(), "RETRYABLE", errorCategory, false),
                job.documentId(), "RETRYABLE");
        return true;
    }

    /** 在租约仍有效时原子失败任务并禁用文档。 */
    @Transactional
    public boolean fail(KnowledgeIndexJob job, String errorCategory, Instant now) {
        if (!jobRepository.lockProcessing(job.id())) {
            return false;
        }
        requireJobTransition(jobRepository.fail(job.id(), errorCategory, now), job.id());
        requireDocumentState(documentRepository.updateIndexStatus(
                job.documentId(), "FAILED", errorCategory, false),
                job.documentId(), "FAILED");
        return true;
    }

    private void requireJobTransition(boolean transitioned, Long jobId) {
        if (!transitioned) {
            throw new IllegalStateException("索引任务状态提交失败：jobId=" + jobId);
        }
    }

    private void requireDocumentState(boolean updated, Long documentId, String state) {
        if (!updated) {
            throw new IllegalStateException(
                    "知识文档索引状态提交失败：documentId=" + documentId + "，state=" + state);
        }
    }
}
