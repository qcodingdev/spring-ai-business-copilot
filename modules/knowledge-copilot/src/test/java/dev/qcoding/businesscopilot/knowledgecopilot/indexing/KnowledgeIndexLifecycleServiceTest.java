package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeChunkEmbedding;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.PreparedKnowledgeIndex;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeIndexLifecycleServiceTest {

    @Test
    void claimsJobAndDocumentStateTogether() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeIndexLifecycleService service = new KnowledgeIndexLifecycleService(
                jobs, documents, mock(KnowledgeEmbeddingService.class));
        KnowledgeIndexJob job = processingJob();
        Instant now = Instant.now();
        when(jobs.claimNext(now)).thenReturn(Optional.of(job));
        when(documents.updateIndexStatus(1L, "PROCESSING", null, false)).thenReturn(true);

        assertThat(service.claimNext(now)).contains(job);

        verify(documents).updateIndexStatus(1L, "PROCESSING", null, false);
    }

    @Test
    void canceledWorkerCannotMutateVectorsOrDocumentState() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeIndexLifecycleService service = new KnowledgeIndexLifecycleService(
                jobs, documents, embeddings);
        KnowledgeIndexJob job = processingJob();
        PreparedKnowledgeIndex prepared = preparedIndex();
        when(jobs.lockProcessing(10L)).thenReturn(false);

        assertThat(service.completeWithEmbeddings(job, prepared, Instant.now())).isFalse();

        verifyNoInteractions(embeddings);
        verify(jobs, never()).complete(any(), any(), any(Integer.class), any(Integer.class), any());
        verifyNoInteractions(documents);
    }

    @Test
    void validLeaseReplacesVectorsBeforeCompletingTaskAndDocument() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeIndexLifecycleService service = new KnowledgeIndexLifecycleService(
                jobs, documents, embeddings);
        KnowledgeIndexJob job = processingJob();
        PreparedKnowledgeIndex prepared = preparedIndex();
        Instant now = Instant.now();
        when(jobs.lockProcessing(10L)).thenReturn(true);
        when(embeddings.persistPreparedIndex(prepared)).thenReturn(prepared.result());
        when(jobs.complete(10L, "embedding", 1536, 1, now)).thenReturn(true);
        when(documents.updateIndexStatus(1L, "INDEXED", null, true)).thenReturn(true);

        assertThat(service.completeWithEmbeddings(job, prepared, now)).isTrue();

        InOrder order = inOrder(jobs, embeddings, documents);
        order.verify(jobs).lockProcessing(10L);
        order.verify(embeddings).persistPreparedIndex(prepared);
        order.verify(jobs).complete(10L, "embedding", 1536, 1, now);
        order.verify(documents).updateIndexStatus(1L, "INDEXED", null, true);
    }

    @Test
    void completionFailureAbortsBeforeDocumentIsEnabled() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeIndexLifecycleService service = new KnowledgeIndexLifecycleService(
                jobs, documents, embeddings);
        KnowledgeIndexJob job = processingJob();
        PreparedKnowledgeIndex prepared = preparedIndex();
        Instant now = Instant.now();
        when(jobs.lockProcessing(10L)).thenReturn(true);
        when(embeddings.persistPreparedIndex(prepared)).thenReturn(prepared.result());
        when(jobs.complete(10L, "embedding", 1536, 1, now)).thenReturn(false);

        assertThatThrownBy(() -> service.completeWithEmbeddings(job, prepared, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jobId=10");
        verify(documents, never()).updateIndexStatus(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void retryTransitionIsFencedByTheProcessingLease() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeIndexLifecycleService service = new KnowledgeIndexLifecycleService(
                jobs, documents, mock(KnowledgeEmbeddingService.class));
        KnowledgeIndexJob job = processingJob();
        Instant now = Instant.now();
        Instant next = now.plusSeconds(60);
        when(jobs.lockProcessing(10L)).thenReturn(true);
        when(jobs.retry(10L, "INDEXING_FAILED", next, now)).thenReturn(true);
        when(documents.updateIndexStatus(1L, "RETRYABLE", "INDEXING_FAILED", false)).thenReturn(true);

        assertThat(service.retry(job, "INDEXING_FAILED", next, now)).isTrue();
        verify(documents).updateIndexStatus(1L, "RETRYABLE", "INDEXING_FAILED", false);
    }

    private KnowledgeIndexJob processingJob() {
        Instant now = Instant.now();
        return new KnowledgeIndexJob(
                10L, 1L, KnowledgeIndexJobStatus.PROCESSING, 1,
                null, null, null, null, now, now, null, now, now);
    }

    private PreparedKnowledgeIndex preparedIndex() {
        return new PreparedKnowledgeIndex(
                new EmbeddingIndexResult(1L, 1, "embedding", 1536),
                List.of(new KnowledgeChunkEmbedding(
                        null, 11L, "embedding", new float[1536], null)));
    }
}
