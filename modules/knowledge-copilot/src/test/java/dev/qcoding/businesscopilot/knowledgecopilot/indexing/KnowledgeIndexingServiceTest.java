package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeChunkEmbedding;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.PreparedKnowledgeIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexingServiceTest {

    @Test
    void completesAsTextSearchOnlyWhenEmbeddingModelIsDisabled() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeIndexLifecycleService lifecycle = mock(KnowledgeIndexLifecycleService.class);
        KnowledgeIndexingService service = new KnowledgeIndexingService(
                jobs, documents, chunks, embeddings, lifecycle);

        KnowledgeIndexJob claimed = job(KnowledgeIndexJobStatus.PROCESSING, 1);
        KnowledgeIndexJob completed = new KnowledgeIndexJob(
                10L, 1L, KnowledgeIndexJobStatus.COMPLETED, 1,
                "text-search-only", 0, 1, null,
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), Instant.now());
        KnowledgeChunk chunk = new KnowledgeChunk(
                11L, 1L, "年假政策", 0, "入职满一年有五天年假", "入职满一年有五天年假", 10, null);

        when(lifecycle.claimNext(any())).thenReturn(Optional.of(claimed));
        when(chunks.findByDocumentId(1L)).thenReturn(List.of(chunk));
        when(embeddings.prepareIndex(1L, List.of(chunk)))
                .thenThrow(new AiModelNotEnabledException("disabled"));
        when(lifecycle.completeTextSearchOnly(eq(claimed), eq(1), any())).thenReturn(true);
        when(jobs.findById(10L)).thenReturn(Optional.of(completed));

        assertThat(service.processOne()).contains(completed);
        verify(lifecycle).completeTextSearchOnly(eq(claimed), eq(1), any());
    }

    @Test
    void replacesAnOrphanedProcessingJobAfterTheRecoveryWindow() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeIndexingService service = new KnowledgeIndexingService(
                jobs, documents, mock(KnowledgeChunkRepository.class),
                mock(KnowledgeEmbeddingService.class), mock(KnowledgeIndexLifecycleService.class));
        Instant staleAt = Instant.now().minus(Duration.ofMinutes(16));
        KnowledgeIndexJob stale = new KnowledgeIndexJob(
                10L, 1L, KnowledgeIndexJobStatus.PROCESSING, 1,
                null, null, null, null, staleAt, staleAt, null, staleAt, staleAt);
        KnowledgeIndexJob replacement = new KnowledgeIndexJob(
                11L, 1L, KnowledgeIndexJobStatus.PENDING, 0,
                null, null, null, null, Instant.now(), null, null,
                Instant.now(), Instant.now());
        when(jobs.findActiveByDocumentId(1L)).thenReturn(Optional.of(stale));
        when(jobs.cancelStaleProcessing(eq(10L), any(), any())).thenReturn(true);
        when(jobs.enqueue(1L)).thenReturn(replacement);

        assertThat(service.ensureQueued(1L, Duration.ofMinutes(15))).isEqualTo(replacement);
        verify(jobs).cancelStaleProcessing(eq(10L), any(), any());
        verify(documents).updateIndexStatus(1L, "PENDING", null, false);
    }

    @Test
    void aCanceledStaleWorkerCannotOverwriteTheReplacementDocumentState() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeIndexLifecycleService lifecycle = mock(KnowledgeIndexLifecycleService.class);
        KnowledgeIndexingService service = new KnowledgeIndexingService(
                jobs, documents, chunks, embeddings, lifecycle);
        KnowledgeIndexJob claimed = job(KnowledgeIndexJobStatus.PROCESSING, 1);
        KnowledgeIndexJob canceled = new KnowledgeIndexJob(
                10L, 1L, KnowledgeIndexJobStatus.CANCELED, 1,
                null, null, null, "STALE_PROCESSING_RECOVERED",
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), Instant.now());
        KnowledgeChunk chunk = new KnowledgeChunk(
                11L, 1L, "年假政策", 0, "正文", "正文", 2, null);
        PreparedKnowledgeIndex prepared = new PreparedKnowledgeIndex(
                new EmbeddingIndexResult(1L, 1, "embedding", 1536),
                List.of(new KnowledgeChunkEmbedding(
                        null, 11L, "embedding", new float[1536], null)));
        when(lifecycle.claimNext(any())).thenReturn(Optional.of(claimed));
        when(chunks.findByDocumentId(1L)).thenReturn(List.of(chunk));
        when(embeddings.prepareIndex(1L, List.of(chunk))).thenReturn(prepared);
        when(lifecycle.completeWithEmbeddings(eq(claimed), eq(prepared), any())).thenReturn(false);
        when(jobs.findById(10L)).thenReturn(Optional.of(canceled));

        assertThat(service.processOne()).contains(canceled);
        verify(lifecycle).completeWithEmbeddings(eq(claimed), eq(prepared), any());
        verify(embeddings, never()).persistPreparedIndex(any());
    }

    private KnowledgeIndexJob job(KnowledgeIndexJobStatus status, int attempts) {
        return new KnowledgeIndexJob(
                10L, 1L, status, attempts, null, null, null, null,
                Instant.now(), null, null, Instant.now(), Instant.now());
    }
}
