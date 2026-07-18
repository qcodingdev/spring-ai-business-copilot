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
 * Validates that model-selected citation IDs reference only retrieved chunks.
 *
 * <p>引用完整性校验服务。对模型选择的 citations 进行合法性检查：
 * <ul>
 *   <li>ANSWERED 状态必须至少有一个 citation</li>
 *   <li>每个 citation 的 chunkId 必须来自本次检索的 retrieved chunks</li>
 *   <li>不存在引用不存在的 chunkId（防止模型幻觉）</li>
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
            violations.add("ANSWERED 状态至少需要一条引用，但模型未返回引用");
            log.warn("引用校验失败：模型未返回引用");
            return new CitationValidationResult(false, violations);
        }

        Set<Long> validChunkIds = retrievedChunks.stream()
                .map(r -> r.chunk().id())
                .collect(Collectors.toSet());

        for (KnowledgeCitation citation : citations) {
            if (citation.chunkId() == null) {
                violations.add("引用中的 chunkId 不能为空");
                continue;
            }
            if (!validChunkIds.contains(citation.chunkId())) {
                violations.add("引用的 chunkId=" + citation.chunkId()
                        + " 不在本次召回结果中；允许的 chunkId 为 " + validChunkIds);
            }
        }

        boolean valid = violations.isEmpty();
        if (!valid) {
            log.warn("引用校验失败：共 {} 个问题", violations.size());
        } else {
            log.debug("引用校验通过：{} 条引用，候选分片共 {} 个",
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
