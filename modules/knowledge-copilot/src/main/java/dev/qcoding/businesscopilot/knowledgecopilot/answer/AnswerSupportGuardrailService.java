package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import dev.qcoding.businesscopilot.guardrails.EvidenceClaimGrounding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KNOW-02：答案支持性的确定性守卫。
 *
 * <p>检查答案中的硬事实与主要陈述是否能被引用证据支撑：数字必须能回溯到引用分片，
 * 文本陈述必须与引用证据有足够的词面重合；高风险的绝对化、因果和趋势结论使用更严格门槛。
 * 任一检查失败时整体拒绝返回，避免模型编造数字、夸大或拼接不存在的结论。</p>
 *
 * <p>边界：该守卫是确定性的词面支持性检查，不替代自然语言推理或人工复核；中文数字与
 * 阿拉伯数字的互转、同义改写和跨句推理仍由固定评测集覆盖。单个数字不检查，避免
 * "5"与"五"这类等价写法造成误拒。</p>
 */
public class AnswerSupportGuardrailService {

    private static final Pattern DIGIT_RUNS = Pattern.compile("\\d{2,}");
    private final EvidenceClaimGrounding claimGrounding = new EvidenceClaimGrounding();

    public SupportAssessment assess(String answer,
                                    List<KnowledgeCitation> citations,
                                    List<RetrievedKnowledgeChunk> retrievedChunks) {
        double citationValidity = citationValidity(citations, retrievedChunks);
        double excerptGroundedness = excerptGroundedness(citations);

        if (citations == null || citations.isEmpty()) {
            return new SupportAssessment(false,
                    List.of("答案缺少可核验引用，不支持直接返回"),
                    citationValidity, excerptGroundedness);
        }

        String evidenceText = citedEvidenceText(citations, retrievedChunks);
        List<String> violations = new ArrayList<>();
        if (evidenceText.isEmpty()) {
            violations.add("引用分片内容为空，无法核验答案细节");
        } else {
            Set<String> unsupported = new HashSet<>();
            Matcher matcher = DIGIT_RUNS.matcher(answer == null ? "" : answer);
            while (matcher.find()) {
                String run = matcher.group();
                if (!evidenceText.contains(run)) {
                    unsupported.add(run);
                }
            }
            if (!unsupported.isEmpty()) {
                violations.add("答案中的数字细节未出现在引用证据中：" + String.join("、", unsupported));
            }
            EvidenceClaimGrounding.Assessment claimAssessment =
                    claimGrounding.assess(answer, evidenceText);
            if (!claimAssessment.supported()) {
                violations.add("答案中的陈述缺少引用证据词面支撑："
                        + String.join("；", claimAssessment.unsupportedClaims()));
            }
        }
        return new SupportAssessment(violations.isEmpty(), List.copyOf(violations),
                citationValidity, excerptGroundedness);
    }

    /** 引用中能对应到本次召回分片的比例；对应不到的分片不构成支撑。 */
    private double citationValidity(List<KnowledgeCitation> citations,
                                    List<RetrievedKnowledgeChunk> retrievedChunks) {
        if (citations == null || citations.isEmpty()) {
            return 0d;
        }
        Map<Long, RetrievedKnowledgeChunk> byChunkId = new LinkedHashMap<>();
        for (RetrievedKnowledgeChunk retrieved : retrievedChunks == null ? List.<RetrievedKnowledgeChunk>of() : retrievedChunks) {
            byChunkId.put(retrieved.chunk().id(), retrieved);
        }
        long grounded = citations.stream()
                .filter(citation -> byChunkId.containsKey(citation.chunkId()))
                .count();
        return (double) grounded / citations.size();
    }

    /** 引用摘录可回溯比例：服务端摘录为空说明证据内容缺失，引用不可核验。 */
    private double excerptGroundedness(List<KnowledgeCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return 0d;
        }
        long withExcerpt = citations.stream()
                .filter(citation -> citation.excerpt() != null && !citation.excerpt().isBlank())
                .count();
        return (double) withExcerpt / citations.size();
    }

    private String citedEvidenceText(List<KnowledgeCitation> citations,
                                     List<RetrievedKnowledgeChunk> retrievedChunks) {
        Map<Long, RetrievedKnowledgeChunk> byChunkId = new LinkedHashMap<>();
        for (RetrievedKnowledgeChunk retrieved : retrievedChunks == null ? List.<RetrievedKnowledgeChunk>of() : retrievedChunks) {
            byChunkId.put(retrieved.chunk().id(), retrieved);
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeCitation citation : citations) {
            RetrievedKnowledgeChunk retrieved = byChunkId.get(citation.chunkId());
            if (retrieved == null) {
                continue;
            }
            if (retrieved.chunk().content() != null) {
                sb.append(retrieved.chunk().content()).append('\n');
            }
            if (retrieved.chunk().contentPreview() != null) {
                sb.append(retrieved.chunk().contentPreview()).append('\n');
            }
        }
        // 保留英文词边界供词面重合校验使用；精确包含校验会自行归一化空白和标点。
        return sb.toString();
    }

    public record SupportAssessment(boolean supported, List<String> violations,
                                    double citationValidity, double excerptGroundedness) {
    }
}
