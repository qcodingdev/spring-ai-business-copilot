package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeQueryTermsTest {

    @Test
    void extractsBusinessTermsFromChineseQuestionWithoutDatabaseTokenizer() {
        assertThat(KnowledgeQueryTerms.extract("请问公司年假政策是什么？"))
                .contains("年假政策", "年假", "政策");
    }

    @Test
    void keepsEnglishProductTermsAndBoundsOutput() {
        assertThat(KnowledgeQueryTerms.extract("CloudMart API 批量导入 SKU 上限是多少？"))
                .contains("cloudmart", "api", "sku")
                .hasSizeLessThanOrEqualTo(32);
    }

    @Test
    void returnsEmptyTermsForBlankQuestion() {
        assertThat(KnowledgeQueryTerms.extract("  ")).isEmpty();
    }
}
