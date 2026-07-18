package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

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

    public KnowledgeIndexingService(KnowledgeIndexJobRepository jobRepository,
                                    KnowledgeDocumentRepository documentRepository,
                                    KnowledgeChunkRepository chunkRepository,
                                    KnowledgeEmbeddingService embeddingService) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
    }

    public KnowledgeIndexJob enqueue(Long documentId) {
        documentRepository.updateIndexStatus(documentId, "PENDING", null, false);
        return jobRepository.enqueue(documentId);
    }

    @Scheduled(fixedDelayString = "${business-copilot.knowledge.index-worker-delay:5000}")
    public void processPendingJob() {
        processOne();
    }

    public Optional<KnowledgeIndexJob> processOne() {
        Instant now = Instant.now();
        Optional<KnowledgeIndexJob> claimed = jobRepository.claimNext(now);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        KnowledgeIndexJob job = claimed.get();
        documentRepository.updateIndexStatus(job.documentId(), "PROCESSING", null, false);
        var chunks = chunkRepository.findByDocumentId(job.documentId());
        try {
            EmbeddingIndexResult result = embeddingService.reindex(
                    job.documentId(), chunks);
            Instant finished = Instant.now();
            jobRepository.complete(job.id(), result.modelName(), result.dimension(), result.chunkCount(), finished);
            documentRepository.updateIndexStatus(job.documentId(), "INDEXED", null, result.chunkCount() > 0);
            return jobRepository.findById(job.id());
        } catch (AiModelNotEnabledException ex) {
            // 文本分片本身已经持久化。未配置向量模型时完成文本索引并启用文档，
            // 让 PostgreSQL 全文/关键词检索仍可服务知识问答和客服联动。
            Instant finished = Instant.now();
            jobRepository.complete(job.id(), "text-search-only", 0, chunks.size(), finished);
            documentRepository.updateIndexStatus(
                    job.documentId(), "INDEXED", "TEXT_SEARCH_ONLY", !chunks.isEmpty());
            log.info("Embedding 模型未启用，知识文档已降级为文本检索：jobId={}，documentId={}，chunks={}",
                    job.id(), job.documentId(), chunks.size());
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
        jobRepository.fail(job.id(), errorCategory, now);
        documentRepository.updateIndexStatus(job.documentId(), "FAILED", errorCategory, false);
        log.warn("知识索引任务不可重试，已标记失败：jobId={}，documentId={}，原因={}",
                job.id(), job.documentId(), errorCategory);
    }

    private void retryOrFail(KnowledgeIndexJob job, String errorCategory) {
        Instant now = Instant.now();
        if (job.attempts() >= MAX_ATTEMPTS) {
            jobRepository.fail(job.id(), errorCategory, now);
            documentRepository.updateIndexStatus(job.documentId(), "FAILED", errorCategory, false);
            return;
        }
        Duration delay = Duration.ofMinutes(1L << Math.max(0, job.attempts() - 1));
        jobRepository.retry(job.id(), errorCategory, now.plus(delay), now);
        documentRepository.updateIndexStatus(job.documentId(), "RETRYABLE", errorCategory, false);
    }
}
