package dev.qcoding.businesscopilot.knowledgecopilot.citation;

import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeCitation;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationGuardrailServiceTest {

    private CitationGuardrailService service;

    @BeforeEach
    void setUp() {
        service = new CitationGuardrailService();
    }

    private static RetrievedKnowledgeChunk retrievedChunk(long id) {
        KnowledgeChunk chunk = new KnowledgeChunk(id, 1L, "Section", 0, "content", "preview", 10, null);
        return new RetrievedKnowledgeChunk(chunk, 0.95, "test-model");
    }

    @Test
    void validCitationsPassValidation() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L), retrievedChunk(2L));
        List<KnowledgeCitation> citations = List.of(
                new KnowledgeCitation(1L, "excerpt"),
                new KnowledgeCitation(2L, "another excerpt"));

        CitationGuardrailService.CitationValidationResult result = service.validate(citations, retrieved);

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void emptyCitationsFailValidation() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L));
        List<KnowledgeCitation> citations = List.of();

        CitationGuardrailService.CitationValidationResult result = service.validate(citations, retrieved);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("at least one citation"));
    }

    @Test
    void nullCitationsFailValidation() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L));

        CitationGuardrailService.CitationValidationResult result = service.validate(null, retrieved);

        assertThat(result.valid()).isFalse();
    }

    @Test
    void citationWithNonExistentChunkIdFailsValidation() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L));
        List<KnowledgeCitation> citations = List.of(new KnowledgeCitation(999L, "phantom chunk"));

        CitationGuardrailService.CitationValidationResult result = service.validate(citations, retrieved);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("999"));
        assertThat(result.violations()).anyMatch(v -> v.contains("not among the retrieved chunks"));
    }

    @Test
    void citationWithNullChunkIdFailsValidation() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L));
        List<KnowledgeCitation> citations = List.of(new KnowledgeCitation(null, "no id"));

        CitationGuardrailService.CitationValidationResult result = service.validate(citations, retrieved);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("null chunkId"));
    }

    @Test
    void mixedValidAndInvalidCitationsFail() {
        List<RetrievedKnowledgeChunk> retrieved = List.of(retrievedChunk(1L), retrievedChunk(2L));
        List<KnowledgeCitation> citations = List.of(
                new KnowledgeCitation(1L, "valid"),
                new KnowledgeCitation(999L, "invalid"));

        CitationGuardrailService.CitationValidationResult result = service.validate(citations, retrieved);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0)).contains("999");
    }
}
