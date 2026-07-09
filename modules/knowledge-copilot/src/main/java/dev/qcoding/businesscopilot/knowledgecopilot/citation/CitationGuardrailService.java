package dev.qcoding.businesscopilot.knowledgecopilot.citation;

import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeCitation;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that LLM-generated citations are complete and reference only existing chunks.
 *
 * <p>引用完整性校验服务。对模型输出的 citations 进行合法性检查：
 * <ul>
 *   <li>ANSWERED 状态必须至少有一个 citation</li>
 *   <li>每个 citation 的 chunkId 必须来自本次检索的 retrieved chunks</li>
 *   <li>不存在引用不存在的 chunkId（防止 LLM 幻觉）</li>
 * </ul>
 * 校验不通过时返回拒绝理由，由调用方决定降级为 REJECTED 或 NO_EVIDENCE。</p>
 */
public class CitationGuardrailService {

    private static final Logger log = LoggerFactory.getLogger(CitationGuardrailService.class);

    /**
     * Validate citations against the retrieved chunks.
     *
     * @param citations        the citations from the LLM output
     * @param retrievedChunks  the chunks that were actually retrieved and provided as context
     * @return a {@link CitationValidationResult} with violations if any
     */
    public CitationValidationResult validate(List<KnowledgeCitation> citations,
                                              List<RetrievedKnowledgeChunk> retrievedChunks) {
        List<String> violations = new ArrayList<>();

        if (citations == null || citations.isEmpty()) {
            violations.add("ANSWERED status requires at least one citation, but none were provided");
            log.warn("Citation validation failed: no citations provided");
            return new CitationValidationResult(false, violations);
        }

        Set<Long> validChunkIds = retrievedChunks.stream()
                .map(r -> r.chunk().id())
                .collect(Collectors.toSet());

        for (KnowledgeCitation citation : citations) {
            if (citation.chunkId() == null) {
                violations.add("Citation contains null chunkId");
                continue;
            }
            if (!validChunkIds.contains(citation.chunkId())) {
                violations.add("Citation references chunkId=" + citation.chunkId()
                        + " which was not among the retrieved chunks. "
                        + "Valid chunk IDs: " + validChunkIds);
            }
        }

        boolean valid = violations.isEmpty();
        if (!valid) {
            log.warn("Citation validation failed: {} violation(s)", violations.size());
        } else {
            log.debug("Citation validation passed: {} citation(s) referencing {} chunk(s)",
                    citations.size(), validChunkIds.size());
        }

        return new CitationValidationResult(valid, violations);
    }

    /**
     * Result of citation guardrail validation.
     *
     * @param valid      whether all citations passed validation
     * @param violations list of violation descriptions; empty if valid
     */
    public record CitationValidationResult(boolean valid, List<String> violations) {
    }
}
