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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    private final AiEmbeddingService embeddings = mock(AiEmbeddingService.class);
    private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
    private final KnowledgeRetrievalService service = new KnowledgeRetrievalService(
            embeddings,
            mock(KnowledgeEmbeddingRepository.class),
            chunks,
            new KnowledgeCopilotProperties(
                    true, 2_097_152, 5, 0.70, "text-embedding-3-small", 1536));

    @Test
    void returnsChineseKeywordResultWhenEmbeddingModelIsDisabled() {
        KnowledgeChunk chunk = new KnowledgeChunk(
                11L, 1L, "年假政策", 0,
                "员工入职满一年可享受五天带薪年假。",
                "员工入职满一年可享受五天带薪年假。", 20, null);
        when(chunks.findByTextSearch(anyString(), anyInt())).thenReturn(List.of());
        when(chunks.findByKeywordSearch(any(), anyInt()))
                .thenReturn(List.of(new KnowledgeChunkRepository.TextSearchResult(11L, 0.8)));
        when(chunks.findById(11L)).thenReturn(Optional.of(chunk));
        when(chunks.isDocumentVisible(1L)).thenReturn(true);
        when(embeddings.embed(anyString(), anyString()))
                .thenThrow(new AiModelNotEnabledException("disabled"));

        List<RetrievedKnowledgeChunk> result = service.retrieve("公司年假政策是什么？");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().chunk().id()).isEqualTo(11L);
        assertThat(result.getFirst().similarity()).isEqualTo(0.8);
    }

    @Test
    void dropsChunkWhenDocumentIsInvalidatedBeforeConsumption() {
        // KNOW-03：检索命中与回表加载之间存在时间窗口，文档失效的分片必须丢弃。
        KnowledgeChunk chunk = new KnowledgeChunk(11L, 1L, "年假政策", 0,
                "员工入职满一年可享受五天带薪年假。",
                "员工入职满一年可享受五天带薪年假。", 20, null);
        when(chunks.findByTextSearch(anyString(), anyInt()))
                .thenReturn(List.of(new KnowledgeChunkRepository.TextSearchResult(11L, 0.9)));
        when(chunks.findById(11L)).thenReturn(Optional.of(chunk));
        when(chunks.isDocumentVisible(1L)).thenReturn(false);

        assertThat(service.retrieve("公司年假政策是什么？")).isEmpty();
    }

    @Test
    void runsAtMostOneSupplementalPassWhenFirstPassFindsNothing() {
        // KNOW-05：首次检索未命中 → 一次放宽阈值的补充检索；补充命中后不得继续扩大范围。
        KnowledgeChunk chunk = new KnowledgeChunk(11L, 1L, "年假政策", 0,
                "员工入职满一年可享受五天带薪年假。",
                "员工入职满一年可享受五天带薪年假。", 20, null);
        when(chunks.findByTextSearch(anyString(), eq(10))).thenReturn(List.of());
        when(chunks.findByKeywordSearch(any(), eq(10))).thenReturn(List.of());
        when(chunks.findByKeywordSearch(any(), eq(15)))
                .thenReturn(List.of(new KnowledgeChunkRepository.TextSearchResult(11L, 0.55)));
        when(chunks.findById(11L)).thenReturn(Optional.of(chunk));
        when(chunks.isDocumentVisible(1L)).thenReturn(true);

        KnowledgeRetrievalService.RetrievalOutcome outcome =
                service.retrieveWithGapCheck("公司年假政策是什么？", null);

        assertThat(outcome.supplementalUsed()).isTrue();
        assertThat(outcome.evidence()).singleElement()
                .satisfies(item -> assertThat(item.chunk().id()).isEqualTo(11L));
        verify(chunks, never()).findByKeywordSearch(any(), eq(20));
    }

    @Test
    void skipsSupplementalPassWhenFirstPassHasEvidence() {
        KnowledgeChunk chunk = new KnowledgeChunk(11L, 1L, "年假政策", 0,
                "员工入职满一年可享受五天带薪年假。",
                "员工入职满一年可享受五天带薪年假。", 20, null);
        when(chunks.findByTextSearch(anyString(), eq(10)))
                .thenReturn(List.of(new KnowledgeChunkRepository.TextSearchResult(11L, 0.9)));
        when(chunks.findById(11L)).thenReturn(Optional.of(chunk));
        when(chunks.isDocumentVisible(1L)).thenReturn(true);

        KnowledgeRetrievalService.RetrievalOutcome outcome =
                service.retrieveWithGapCheck("公司年假政策是什么？", null);

        assertThat(outcome.supplementalUsed()).isFalse();
        assertThat(outcome.evidence()).hasSize(1);
        verify(chunks, never()).findByKeywordSearch(any(), eq(15));
    }

    @Test
    void supplementalPassStaysEmptyWhenGapRemains() {
        // 补充检索后仍无结果：保持空证据，由上游拒答，不无限扩大检索。
        when(chunks.findByTextSearch(anyString(), anyInt())).thenReturn(List.of());
        when(chunks.findByKeywordSearch(any(), anyInt())).thenReturn(List.of());

        KnowledgeRetrievalService.RetrievalOutcome outcome =
                service.retrieveWithGapCheck("完全不相关的问题", null);

        assertThat(outcome.supplementalUsed()).isTrue();
        assertThat(outcome.evidence()).isEmpty();
    }
}
