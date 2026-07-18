package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    @Test
    void returnsChineseKeywordResultWhenEmbeddingModelIsDisabled() {
        AiEmbeddingService embeddings = mock(AiEmbeddingService.class);
        KnowledgeEmbeddingRepository embeddingRepository = mock(KnowledgeEmbeddingRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                embeddings,
                embeddingRepository,
                chunks,
                new KnowledgeCopilotProperties(
                        true, 2_097_152, 5, 0.70, "text-embedding-3-small", 1536));

        KnowledgeChunk chunk = new KnowledgeChunk(
                11L, 1L, "年假政策", 0,
                "员工入职满一年可享受五天带薪年假。",
                "员工入职满一年可享受五天带薪年假。", 20, null);
        when(chunks.findByTextSearch(anyString(), anyInt())).thenReturn(List.of());
        when(chunks.findByKeywordSearch(any(), anyInt()))
                .thenReturn(List.of(new KnowledgeChunkRepository.TextSearchResult(11L, 0.8)));
        when(chunks.findById(11L)).thenReturn(Optional.of(chunk));
        when(embeddings.embed(anyString()))
                .thenThrow(new AiModelNotEnabledException("disabled"));

        List<RetrievedKnowledgeChunk> result = service.retrieve("公司年假政策是什么？");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().chunk().id()).isEqualTo(11L);
        assertThat(result.getFirst().similarity()).isEqualTo(0.8);
    }
}
