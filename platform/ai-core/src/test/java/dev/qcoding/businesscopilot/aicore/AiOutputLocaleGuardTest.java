package dev.qcoding.businesscopilot.aicore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiOutputLocaleGuardTest {

    private final AiOutputLocaleGuard guard = new AiOutputLocaleGuard();

    @Test
    void validatesVisibleNaturalLanguageAndIgnoresSqlAndCitations() {
        assertThat(guard.complies(
                new Output("已完成查询。", "SELECT name FROM orders LIMIT 1",
                        List.of(new Citation("Refund policy remains unchanged."))),
                "zh-CN")).isTrue();
        assertThat(guard.complies(
                new Output("Query completed.", "SELECT 名称 FROM orders LIMIT 1",
                        List.of(new Citation("退款政策原文。"))),
                "en-US")).isTrue();
    }

    @Test
    void rejectsWrongExplicitLanguage() {
        assertThat(guard.complies(new Output(
                "This answer is in the wrong language.", null, List.of()), "zh-CN")).isFalse();
        assertThat(guard.complies(new Output(
                "这个答案使用了错误语言。", null, List.of()), "en-US")).isFalse();
    }

    private record Output(String answer, String sql, List<Citation> citations) {
    }

    private record Citation(String excerpt) {
    }
}
