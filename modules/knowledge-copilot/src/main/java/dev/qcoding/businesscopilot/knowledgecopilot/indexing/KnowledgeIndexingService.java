package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 知识库异步索引任务处理器。 */
public class KnowledgeIndexingService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final KnowledgeIndexJobRepository jobRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeEmbeddingService embeddingService;
    private final KnowledgeIndexLifecycleService lifecycleService;

    public KnowledgeIndexingService(KnowledgeIndexJobRepository jobRepository,
                                    KnowledgeDocumentRepository documentRepository,
                                    KnowledgeChunkRepository chunkRepository,
                                    KnowledgeEmbeddingService embeddingService,
                                    KnowledgeIndexLifecycleService lifecycleService) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.lifecycleService = lifecycleService;
    }

    public KnowledgeIndexJob enqueue(Long documentId) {
        documentRepository.updateIndexStatus(documentId, "PENDING", null, false);
        return jobRepository.enqueue(documentId);
    }

    /**
     * Return the active job or atomically replace an orphaned PROCESSING lease.
     * The conditional cancellation prevents two administrators from creating
     * competing replacement jobs for the same stale attempt.
     */
    @Transactional
    public KnowledgeIndexJob ensureQueued(Long documentId, Duration staleAfter) {
        Optional<KnowledgeIndexJob> active = jobRepository.findActiveByDocumentId(documentId);
        if (active.isEmpty()) {
            return enqueue(documentId);
        }
        KnowledgeIndexJob job = active.get();
        Instant now = Instant.now();
        if (job.status() == KnowledgeIndexJobStatus.PROCESSING
                && job.updatedAt() != null
                && !job.updatedAt().isAfter(now.minus(staleAfter))) {
            if (jobRepository.cancelStaleProcessing(job.id(), now.minus(staleAfter), now)) {
                return enqueue(documentId);
            }
            // worker 可能刚完成，也可能另一恢复请求已经创建替代任务；返回数据库最新状态。
            return jobRepository.findActiveByDocumentId(documentId)
                    .or(() -> jobRepository.findById(job.id()))
                    .orElse(job);
        }
        return job;
    }

    @Scheduled(fixedDelayString = "${business-copilot.knowledge.index-worker-delay:5000}")
    public void processPendingJob() {
        processOne();
    }

    public Optional<KnowledgeIndexJob> processOne() {
        Instant now = Instant.now();
        Optional<KnowledgeIndexJob> claimed = lifecycleService.claimNext(now);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        KnowledgeIndexJob job = claimed.get();
        var chunks = chunkRepository.findByDocumentId(job.documentId());
        try {
            var prepared = embeddingService.prepareIndex(job.documentId(), chunks);
            Instant finished = Instant.now();
            lifecycleService.completeWithEmbeddings(job, prepared, finished);
            return jobRepository.findById(job.id());
        } catch (AiModelNotEnabledException ex) {
            // 文本分片本身已经持久化。未配置向量模型时完成文本索引并启用文档，
            // 让 PostgreSQL 全文/关键词检索仍可服务知识问答和客服联动。
            Instant finished = Instant.now();
            if (lifecycleService.completeTextSearchOnly(job, chunks.size(), finished)) {
                log.info("Embedding 模型未启用，知识文档已降级为文本检索：jobId={}，documentId={}，chunks={}",
                        job.id(), job.documentId(), chunks.size());
            } else {
                log.info("索引任务租约已失效，忽略旧 worker 的文本检索降级结果：jobId={}，documentId={}",
                        job.id(), job.documentId());
            }
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.EMBEDDING_DIMENSION_MISMATCH) {
                failImmediately(job, "EMBEDDING_DIMENSION_MISMATCH");
            } else {
                log.warn("知识索引任务失败，准备按策略重试：jobId={}，documentId={}，errorCode={}",
                        job.id(), job.documentId(), ex.errorCode().code(), ex);
                retryOrFail(job, ex.errorCode().code());
            }
        } catch (RuntimeException ex) {
            log.warn("知识索引任务发生异常，准备按策略重试：jobId={}，documentId={}",
                    job.id(), job.documentId(), ex);
            retryOrFail(job, "INDEXING_FAILED");
        }
        return jobRepository.findById(job.id());
    }

    private void failImmediately(KnowledgeIndexJob job, String errorCategory) {
        Instant now = Instant.now();
        if (lifecycleService.fail(job, errorCategory, now)) {
            log.warn("知识索引任务不可重试，已标记失败：jobId={}，documentId={}，原因={}",
                    job.id(), job.documentId(), errorCategory);
        } else {
            log.info("索引任务租约已失效，忽略旧 worker 的失败结果：jobId={}，documentId={}",
                    job.id(), job.documentId());
        }
    }

    private void retryOrFail(KnowledgeIndexJob job, String errorCategory) {
        Instant now = Instant.now();
        if (job.attempts() >= MAX_ATTEMPTS) {
            lifecycleService.fail(job, errorCategory, now);
            return;
        }
        Duration delay = Duration.ofMinutes(1L << Math.max(0, job.attempts() - 1));
        lifecycleService.retry(job, errorCategory, now.plus(delay), now);
    }
}
