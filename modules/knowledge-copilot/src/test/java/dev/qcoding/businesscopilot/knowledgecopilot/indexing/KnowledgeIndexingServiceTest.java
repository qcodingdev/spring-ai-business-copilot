package dev.qcoding.businesscopilot.knowledgecopilot.indexing;

import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexingServiceTest {

    @Test
    void completesAsTextSearchOnlyWhenEmbeddingModelIsDisabled() {
        KnowledgeIndexJobRepository jobs = mock(KnowledgeIndexJobRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeIndexingService service = new KnowledgeIndexingService(jobs, documents, chunks, embeddings);

        KnowledgeIndexJob claimed = job(KnowledgeIndexJobStatus.PROCESSING, 1);
        KnowledgeIndexJob completed = new KnowledgeIndexJob(
                10L, 1L, KnowledgeIndexJobStatus.COMPLETED, 1,
                "text-search-only", 0, 1, null,
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), Instant.now());
        KnowledgeChunk chunk = new KnowledgeChunk(
                11L, 1L, "年假政策", 0, "入职满一年有五天年假", "入职满一年有五天年假", 10, null);

        when(jobs.claimNext(any())).thenReturn(Optional.of(claimed));
        when(chunks.findByDocumentId(1L)).thenReturn(List.of(chunk));
        when(embeddings.reindex(1L, List.of(chunk)))
                .thenThrow(new AiModelNotEnabledException("disabled"));
        when(jobs.findById(10L)).thenReturn(Optional.of(completed));

        assertThat(service.processOne()).contains(completed);
        verify(jobs).complete(eq(10L), eq("text-search-only"), eq(0), eq(1), any());
        verify(documents).updateIndexStatus(1L, "INDEXED", "TEXT_SEARCH_ONLY", true);
    }

    private KnowledgeIndexJob job(KnowledgeIndexJobStatus status, int attempts) {
        return new KnowledgeIndexJob(
                10L, 1L, status, attempts, null, null, null, null,
                Instant.now(), null, null, Instant.now(), Instant.now());
    }
}
