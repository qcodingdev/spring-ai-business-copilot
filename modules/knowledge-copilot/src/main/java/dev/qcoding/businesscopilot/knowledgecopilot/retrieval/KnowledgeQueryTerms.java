package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为不依赖中文分词扩展的关键词检索提取有限查询词。
 *
 * <p>PostgreSQL {@code simple} 全文配置对英文有效，但不会把连续中文拆成业务词。
 * 这里保留英文/数字词，并为中文片段生成 2 到 4 字的有限 n-gram，作为向量检索
 * 不可用或全文检索未命中时的确定性补充。输出数量有上限，避免查询无限膨胀。</p>
 */
public final class KnowledgeQueryTerms {

    private static final int MAX_TERMS = 32;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[a-z0-9][a-z0-9._-]*");
    private static final Pattern HAN_PATTERN = Pattern.compile("[\\p{IsHan}]+");
    private static final List<String> QUESTION_FILLERS = List.of(
            "请问", "我想了解", "告诉我", "是什么", "有哪些", "哪些", "怎么", "如何",
            "是否", "可以", "需要", "相关", "这个", "那个", "一下", "文档里", "文档中");
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "怎么", "如何", "是否", "可以", "需要", "哪些", "相关",
            "这个", "那个", "一下", "问题", "文档", "内容", "规定", "请问");

    private KnowledgeQueryTerms() {
    }

    public static List<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        for (String filler : QUESTION_FILLERS.stream()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            normalized = normalized.replace(filler, " ");
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find() && terms.size() < MAX_TERMS) {
            String token = matcher.group();
            if (HAN_PATTERN.matcher(token).matches()) {
                addHanTerms(token, terms);
            } else if (token.length() >= 2 && !STOP_TERMS.contains(token)) {
                terms.add(token);
            }
        }
        return List.copyOf(new ArrayList<>(terms).subList(0, Math.min(terms.size(), MAX_TERMS)));
    }

    private static void addHanTerms(String token, LinkedHashSet<String> terms) {
        if (token.length() < 2 || STOP_TERMS.contains(token)) {
            return;
        }
        if (token.length() <= 8) {
            terms.add(token);
        }
        for (int size = Math.min(4, token.length()); size >= 2 && terms.size() < MAX_TERMS; size--) {
            for (int start = 0; start + size <= token.length() && terms.size() < MAX_TERMS; start++) {
                String term = token.substring(start, start + size);
                if (!STOP_TERMS.contains(term)) {
                    terms.add(term);
                }
            }
        }
    }
}
