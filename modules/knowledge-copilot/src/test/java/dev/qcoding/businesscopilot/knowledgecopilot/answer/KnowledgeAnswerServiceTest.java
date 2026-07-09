package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.knowledgecopilot.citation.CitationGuardrailService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAnswerServiceTest {

    private AiChatService aiChatService;
    private PromptTemplateService promptTemplateService;
    private CitationGuardrailService citationGuardrailService;
    private SensitiveTextMasker sensitiveTextMasker;
    private KnowledgeAnswerService service;

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        promptTemplateService = mock(PromptTemplateService.class);
        citationGuardrailService = new CitationGuardrailService();
        sensitiveTextMasker = new SensitiveTextMasker();
        service = new KnowledgeAnswerService(
                aiChatService, promptTemplateService, citationGuardrailService, sensitiveTextMasker);

        when(aiChatService.modelName()).thenReturn("test-model");
        when(promptTemplateService.render(anyString(), anyMap()))
                .thenReturn("rendered prompt");
    }

    private static RetrievedKnowledgeChunk retrievedChunk(long id, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk(id, 1L, "Section", 0, content, "preview", 10, null);
        return new RetrievedKnowledgeChunk(chunk, 0.95, "test-embedding");
    }

    private static LlmAnswerOutput answeredOutput(long chunkId) {
        return new LlmAnswerOutput(
                "ANSWERED",
                "This is the answer.",
                List.of(new LlmAnswerOutput.CitationEntry(chunkId, "excerpt")),
                List.of());
    }

    // ═══════════════════════════════════════════════════════════════
    // 有召回片段时生成 ANSWERED
    // ═══════════════════════════════════════════════════════════════

    @Test
    void answeredWhenRetrievedChunksAreValid() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content A"));
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenReturn(answeredOutput(1L));

        KnowledgeAnswerResponse response = service.answer("test question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
        assertThat(response.answer()).isEqualTo("This is the answer.");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).chunkId()).isEqualTo(1L);
        assertThat(response.modelName()).isEqualTo("test-model");
    }

    // ═══════════════════════════════════════════════════════════════
    // 召回为空时 NO_EVIDENCE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void noEvidenceWhenRetrievedChunksAreEmpty() {
        KnowledgeAnswerResponse response = service.answer("question", List.of());

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);
        assertThat(response.answer()).isNull();
        verify(aiChatService, never()).generateJson(anyString(), any());
    }

    @Test
    void noEvidenceWhenRetrievedChunksAreNull() {
        KnowledgeAnswerResponse response = service.answer("question", null);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);
        verify(aiChatService, never()).generateJson(anyString(), any());
    }

    // ═══════════════════════════════════════════════════════════════
    // 相似度过低时 NO_EVIDENCE（检索返回空 → 不调用 LLM）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void noEvidenceWhenNoChunksMeetSimilarityThreshold() {
        // 检索结果为空模拟相似度过低场景
        KnowledgeAnswerResponse response = service.answer("question", List.of());

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);
        verify(aiChatService, never()).generateJson(anyString(), any());
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM 返回 NO_EVIDENCE 状态
    // ═══════════════════════════════════════════════════════════════

    @Test
    void noEvidenceWhenLlmReturnsNoEvidence() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content"));
        LlmAnswerOutput noEvidenceOutput = new LlmAnswerOutput(
                "NO_EVIDENCE", "", List.of(), List.of("insufficient context"));
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenReturn(noEvidenceOutput);

        KnowledgeAnswerResponse response = service.answer("question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);
        assertThat(response.warnings()).contains("insufficient context");
    }

    // ═══════════════════════════════════════════════════════════════
    // citation 指向不存在 chunk 时拒绝
    // ═══════════════════════════════════════════════════════════════

    @Test
    void rejectedWhenCitationReferencesNonExistentChunk() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content"));
        // LLM 引用不存在的 chunkId=999
        LlmAnswerOutput badCitationOutput = new LlmAnswerOutput(
                "ANSWERED",
                "answer",
                List.of(new LlmAnswerOutput.CitationEntry(999L, "phantom chunk")),
                List.of());
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenReturn(badCitationOutput);

        KnowledgeAnswerResponse response = service.answer("question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.REJECTED);
        assertThat(response.warnings()).anyMatch(w -> w.contains("999"));
    }

    // ═══════════════════════════════════════════════════════════════
    // ANSWERED 无 citation 时拒绝
    // ═══════════════════════════════════════════════════════════════

    @Test
    void rejectedWhenAnsweredButNoCitations() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content"));
        LlmAnswerOutput noCitationOutput = new LlmAnswerOutput(
                "ANSWERED",
                "answer without citation",
                List.of(),
                List.of());
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenReturn(noCitationOutput);

        KnowledgeAnswerResponse response = service.answer("question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.REJECTED);
        assertThat(response.warnings()).anyMatch(w -> w.contains("citation"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 模型 JSON 格式错误时返回 REJECTED
    // ═══════════════════════════════════════════════════════════════

    @Test
    void rejectedWhenJsonParsingFails() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content"));
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR, "unparseable JSON"));

        KnowledgeAnswerResponse response = service.answer("question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.REJECTED);
        assertThat(response.warnings()).anyMatch(w -> w.contains("could not be parsed"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 敏感内容输出被脱敏
    // ═══════════════════════════════════════════════════════════════

    @Test
    void sensitiveContentInAnswerIsMasked() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content"));
        // 答案中包含手机号
        LlmAnswerOutput sensitiveOutput = new LlmAnswerOutput(
                "ANSWERED",
                "请联系 13812345678 获取更多信息。",
                List.of(new LlmAnswerOutput.CitationEntry(1L, "excerpt")),
                List.of());
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenReturn(sensitiveOutput);

        KnowledgeAnswerResponse response = service.answer("question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
        // 手机号应该被脱敏
        assertThat(response.answer()).doesNotContain("13812345678");
        assertThat(response.answer()).contains("****");
        assertThat(response.warnings()).anyMatch(w -> w.contains("masked"));
    }

    @Test
    void secretAssignmentInAnswerIsMasked() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content"));
        LlmAnswerOutput secretOutput = new LlmAnswerOutput(
                "ANSWERED",
                "API key is token=abc123secret for access.",
                List.of(new LlmAnswerOutput.CitationEntry(1L, "excerpt")),
                List.of());
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenReturn(secretOutput);

        KnowledgeAnswerResponse response = service.answer("question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
        assertThat(response.answer()).doesNotContain("abc123secret");
        assertThat(response.answer()).contains("********");
        assertThat(response.warnings()).anyMatch(w -> w.contains("masked"));
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM 返回 null
    // ═══════════════════════════════════════════════════════════════

    @Test
    void rejectedWhenLlmReturnsNull() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L, "content"));
        when(aiChatService.generateJson(anyString(), eq(LlmAnswerOutput.class)))
                .thenReturn(null);

        KnowledgeAnswerResponse response = service.answer("question", retrieved);

        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.REJECTED);
        assertThat(response.warnings()).anyMatch(w -> w.contains("empty output"));
    }
}
