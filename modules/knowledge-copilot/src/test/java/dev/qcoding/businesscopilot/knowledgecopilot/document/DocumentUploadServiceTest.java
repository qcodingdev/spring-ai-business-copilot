package dev.qcoding.businesscopilot.knowledgecopilot.document;

import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentUploadServiceTest {

    private KnowledgeDocumentRepository documentRepository;
    private KnowledgeChunkRepository chunkRepository;
    private ChunkingService chunkingService;
    private KnowledgeEmbeddingService embeddingService;
    private KnowledgeEmbeddingRepository embeddingRepository;
    private DocumentUploadService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(KnowledgeDocumentRepository.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        chunkingService = mock(ChunkingService.class);
        embeddingService = mock(KnowledgeEmbeddingService.class);
        embeddingRepository = mock(KnowledgeEmbeddingRepository.class);
        service = new DocumentUploadService(
                documentRepository,
                chunkRepository,
                new MarkdownDocumentParser(),
                new TextDocumentParser(),
                chunkingService,
                new SensitiveTextMasker(),
                new KnowledgeCopilotProperties(true, 0, 5, 0.70d, "embedding-model", 1536),
                embeddingService,
                embeddingRepository);
    }

    @Test
    void uploadWithoutEmbeddingModelKeepsDocumentDisabledAndRecoverable() {
        KnowledgeChunk chunk = chunk(null);
        KnowledgeChunk savedChunk = chunk(11L);
        when(documentRepository.save(any())).thenReturn(1L);
        when(chunkingService.chunk(any(), any())).thenReturn(List.of(chunk));
        when(chunkRepository.findByDocumentId(1L)).thenReturn(List.of(savedChunk));
        when(embeddingService.indexChunks(1L, List.of(savedChunk)))
                .thenThrow(new AiModelNotEnabledException("disabled"));

        DocumentUploadResponse response = service.upload(new DocumentUploadRequest("guide.md", "# Guide\nBody", null));

        ArgumentCaptor<KnowledgeDocument> document = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentRepository).save(document.capture());
        assertThat(document.getValue().enabled()).isFalse();
        assertThat(response.enabled()).isFalse();
        assertThat(response.indexed()).isFalse();
        verify(documentRepository, never()).updateEnabled(1L, true);
    }

    @Test
    void reindexEnablesDocumentAfterEmbeddingsArePersisted() {
        KnowledgeDocument document = new KnowledgeDocument(
                1L, "Guide", "upload", "guide.md", null, "hash", false, null, null);
        KnowledgeChunk savedChunk = chunk(11L);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocumentId(1L)).thenReturn(List.of(savedChunk));
        when(embeddingService.reindex(1L, List.of(savedChunk)))
                .thenReturn(new EmbeddingIndexResult(1L, 1, "embedding-model", 1536));
        when(documentRepository.updateEnabled(1L, true)).thenReturn(true);

        EmbeddingIndexResult result = service.reindex(1L);

        assertThat(result.chunkCount()).isEqualTo(1);
        verify(documentRepository).updateEnabled(1L, true);
    }

    @Test
    void enablingUnindexedDocumentIsRejected() {
        KnowledgeDocument document = new KnowledgeDocument(
                1L, "Guide", "upload", "guide.md", null, "hash", false, null, null);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(embeddingRepository.existsByDocumentId(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.updateEnabled(1L, true))
                .hasMessageContaining("尚未完成向量索引");
        verify(documentRepository, never()).updateEnabled(1L, true);
    }

    @Test
    void updatingMissingDocumentReturnsFalse() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.updateEnabled(99L, true)).isFalse();

        verify(embeddingRepository, never()).existsByDocumentId(99L);
        verify(documentRepository, never()).updateEnabled(99L, true);
    }

    private static KnowledgeChunk chunk(Long id) {
        return new KnowledgeChunk(id, 1L, "Guide", 0, "Body", "Body", 1, null);
    }
}
