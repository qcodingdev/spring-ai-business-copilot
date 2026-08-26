package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeEmbeddingServiceTest {

    private AiEmbeddingService aiEmbeddingService;
    private KnowledgeEmbeddingRepository embeddingRepository;
    private KnowledgeCopilotProperties properties;
    private KnowledgeEmbeddingService service;

    @BeforeEach
    void setUp() {
        aiEmbeddingService = mock(AiEmbeddingService.class);
        embeddingRepository = mock(KnowledgeEmbeddingRepository.class);
        properties = new KnowledgeCopilotProperties(true, 0, 5, 0.70d, "text-embedding-3-small", 1536);
        service = new KnowledgeEmbeddingService(aiEmbeddingService, embeddingRepository, properties);
    }

    @Test
    void prepareIndexGeneratesVectorsWithoutMutatingTheDatabase() {
        when(aiEmbeddingService.isModelEnabled()).thenReturn(true);
        when(aiEmbeddingService.embed(anyString(), anyString())).thenReturn(new float[1536]);

        KnowledgeChunk chunk1 = new KnowledgeChunk(1L, 1L, "Section 1", 0, "content A", "preview A", 10, null);
        KnowledgeChunk chunk2 = new KnowledgeChunk(2L, 1L, "Section 2", 1, "content B", "preview B", 10, null);

        PreparedKnowledgeIndex prepared = service.prepareIndex(1L, List.of(chunk1, chunk2));
        EmbeddingIndexResult result = prepared.result();

        assertThat(result.documentId()).isEqualTo(1L);
        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.modelName()).isEqualTo("text-embedding-3-small");
        assertThat(result.dimension()).isEqualTo(1536);

        assertThat(prepared.embeddings()).hasSize(2);
        verifyNoInteractions(embeddingRepository);
    }

    @Test
    void persistPreparedIndexReplacesExistingVectors() {
        when(embeddingRepository.deleteByDocumentId(1L)).thenReturn(5);

        KnowledgeChunkEmbedding embedding = new KnowledgeChunkEmbedding(
                null, 10L, "text-embedding-3-small", new float[1536], null);
        PreparedKnowledgeIndex prepared = new PreparedKnowledgeIndex(
                new EmbeddingIndexResult(1L, 1, "text-embedding-3-small", 1536),
                List.of(embedding));
        EmbeddingIndexResult result = service.persistPreparedIndex(prepared);

        verify(embeddingRepository).deleteByDocumentId(1L);
        verify(embeddingRepository).saveAll(List.of(embedding));
        assertThat(result).isEqualTo(prepared.result());
    }

    @Test
    void prepareIndexThrowsOnDimensionMismatchWithoutDeletingExistingVectors() {
        when(aiEmbeddingService.isModelEnabled()).thenReturn(true);
        // 模型返回 768 维，配置期望 1536
        when(aiEmbeddingService.embed(anyString(), anyString())).thenReturn(new float[768]);

        KnowledgeChunk chunk = new KnowledgeChunk(1L, 1L, "Section", 0, "content", "preview", 5, null);

        assertThatThrownBy(() -> service.prepareIndex(1L, List.of(chunk)))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).errorCode() == ErrorCode.EMBEDDING_DIMENSION_MISMATCH)
                .hasMessageContaining("向量维度不匹配");
        verifyNoInteractions(embeddingRepository);
    }

    @Test
    void prepareIndexHandlesEmptyChunkListWithoutPersistence() {
        when(aiEmbeddingService.isModelEnabled()).thenReturn(true);

        PreparedKnowledgeIndex prepared = service.prepareIndex(1L, List.of());
        EmbeddingIndexResult result = prepared.result();

        assertThat(result.documentId()).isEqualTo(1L);
        assertThat(result.chunkCount()).isEqualTo(0);
        assertThat(prepared.embeddings()).isEmpty();
        verify(aiEmbeddingService, never()).embed(anyString(), anyString());
        verifyNoInteractions(embeddingRepository);
    }

    @Test
    void prepareIndexRecordsModelNameInEveryEmbedding() {
        when(aiEmbeddingService.isModelEnabled()).thenReturn(true);
        float[] vector = new float[1536];
        when(aiEmbeddingService.embed(anyString(), anyString())).thenReturn(vector);

        KnowledgeChunk chunk = new KnowledgeChunk(42L, 1L, "Title", 0, "text", "preview", 5, null);
        PreparedKnowledgeIndex prepared = service.prepareIndex(1L, List.of(chunk));

        assertThat(prepared.embeddings())
                .extracting(KnowledgeChunkEmbedding::embeddingModel)
                .containsExactly("text-embedding-3-small");
        verifyNoInteractions(embeddingRepository);
    }

    @Test
    void persistPreparedEmptyIndexDeletesOldVectorsWithoutSavingRows() {
        PreparedKnowledgeIndex prepared = new PreparedKnowledgeIndex(
                new EmbeddingIndexResult(1L, 0, "text-embedding-3-small", 1536),
                List.of());

        service.persistPreparedIndex(prepared);

        verify(embeddingRepository).deleteByDocumentId(1L);
        verify(embeddingRepository, never()).saveAll(any());
    }

    @Test
    void prepareIndexPropagatesEmbeddingModelErrorWithoutDeletingExistingVectors() {
        when(aiEmbeddingService.isModelEnabled()).thenReturn(true);
        when(aiEmbeddingService.embed(anyString(), anyString())).thenThrow(new AiModelNotEnabledException("no model"));

        KnowledgeChunk chunk = new KnowledgeChunk(1L, 1L, "Section", 0, "content", "preview", 5, null);

        assertThatThrownBy(() -> service.prepareIndex(1L, List.of(chunk)))
                .isInstanceOf(AiModelNotEnabledException.class);
        verifyNoInteractions(embeddingRepository);
    }
}
