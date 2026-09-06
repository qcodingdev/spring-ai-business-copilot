package dev.qcoding.businesscopilot.guardrails;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic claim-level lexical grounding for evidence-bound business outputs. */
public class EvidenceClaimGrounding {

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;，,.\\n]+[。！？!?；;，,.]?");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern TERM = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z][A-Za-z0-9_-]{2,}");
    private static final Pattern HIGH_RISK = Pattern.compile(
            "导致|由于|因此|所以|造成|证明|表明|说明|原因|必然|显著|明显|大幅|全部|始终|从未|"
                    + "主要|核心|严重|优于|劣于|caus(?:e|ed|es|ing)|because|therefore|proves?|"
                    + "indicates?|significant(?:ly)?|always|never|all|best|worst",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of(
            "我们", "本次", "当前", "相关", "进行", "已经", "可以", "需要", "以及", "通过",
            "工作", "项目", "方面", "情况", "其中", "同时", "一个", "这个", "that", "this",
            "with", "from", "have", "has", "were", "was", "the", "and", "for", "are");

    public Assessment assess(String claims, String evidence) {
        if (claims == null || claims.isBlank()) {
            return new Assessment(true, List.of());
        }
        if (evidence == null || evidence.isBlank()) {
            return new Assessment(false, List.of(trim(claims)));
        }
        String normalizedEvidence = normalize(evidence);
        Set<String> evidenceTerms = terms(evidence);
        List<String> unsupported = new ArrayList<>();
        Matcher sentences = SENTENCE.matcher(claims);
        while (sentences.find()) {
            String claim = sentences.group().strip();
            Set<String> claimTerms = terms(claim);
            if (claimTerms.size() < 2 || normalize(claim).length() < 6) {
                continue;
            }
            if (normalizedEvidence.contains(normalize(claim))) {
                continue;
            }
            long overlap = claimTerms.stream().filter(evidenceTerms::contains).count();
            double ratio = (double) overlap / claimTerms.size();
            boolean highRisk = HIGH_RISK.matcher(claim).find();
            boolean numericClaim = NUMBER.matcher(claim).find();
            boolean evidenceExpressesSameRisk = !highRisk || HIGH_RISK.matcher(evidence).find();
            int minimumOverlap = highRisk ? 3 : numericClaim ? 1 : 2;
            // Ordinary paraphrases often replace grammatical Chinese bigrams; two distinctive
            // overlaps plus 30% coverage accepts them while high-risk conclusions stay strict.
            double minimumRatio = highRisk ? 0.60d : numericClaim ? 0.15d : 0.30d;
            if (overlap < minimumOverlap || ratio < minimumRatio || !evidenceExpressesSameRisk) {
                unsupported.add(trim(claim));
            }
        }
        return new Assessment(unsupported.isEmpty(), List.copyOf(unsupported));
    }

    private Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            if (term.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.HAN)) {
                int[] points = term.codePoints().toArray();
                if (points.length == 2 && !STOP_WORDS.contains(term)) {
                    result.add(term);
                } else {
                    for (int i = 0; i < points.length - 1; i++) {
                        String gram = new String(points, i, 2);
                        if (!STOP_WORDS.contains(gram)) {
                            result.add(gram);
                        }
                    }
                }
            } else if (!STOP_WORDS.contains(term)) {
                result.add(term);
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String trim(String claim) {
        String stripped = claim == null ? "" : claim.strip();
        return stripped.length() <= 100 ? stripped : stripped.substring(0, 100) + "…";
    }

    public record Assessment(boolean supported, List<String> unsupportedClaims) {
    }
}
