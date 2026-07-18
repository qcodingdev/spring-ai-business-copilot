package dev.qcoding.businesscopilot.knowledgecopilot.citation;

import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeCitation;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCitationEvaluationTest {

    @Test
    void fixedCitationGroundingSetRemainsStable() throws Exception {
        var resource = getClass().getResourceAsStream("/evals/citation-grounding.tsv");
        assertThat(resource).isNotNull();
        List<String> lines = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        CitationGuardrailService guardrail = new CitationGuardrailService();

        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            boolean expected = Boolean.parseBoolean(fields[0]);
            long retrievedChunkId = Long.parseLong(fields[1]);
            Long citedChunkId = fields[2].isBlank() ? null : Long.parseLong(fields[2]);
            KnowledgeChunk chunk = new KnowledgeChunk(
                    retrievedChunkId, 1L, "Policy", 0,
                    "虚构制度证据", "虚构制度证据", 10, null);
            var result = guardrail.validate(
                    List.of(new KnowledgeCitation(citedChunkId, null)),
                    List.of(new RetrievedKnowledgeChunk(chunk, 1.0, "fixed-eval")));
            assertThat(result.valid()).as(line).isEqualTo(expected);
        }
    }
}
