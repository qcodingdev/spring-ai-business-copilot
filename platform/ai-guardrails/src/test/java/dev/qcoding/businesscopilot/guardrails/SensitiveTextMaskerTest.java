package dev.qcoding.businesscopilot.guardrails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveTextMaskerTest {

    private SensitiveTextMasker masker;

    @BeforeEach
    void setUp() {
        masker = new SensitiveTextMasker();
    }

    @Test
    @DisplayName("手机号保留前三后四")
    void phoneMasked() {
        String result = masker.mask("联系电话 13812345678 请记录");
        assertThat(result).contains("138****5678");
        assertThat(result).doesNotContain("13812345678");
    }

    @Test
    @DisplayName("邮箱保留首字符和域名")
    void emailMasked() {
        String result = masker.mask("发送到 user001@example.com 即可");
        assertThat(result).contains("u***@example.com");
        assertThat(result).doesNotContain("user001@example.com");
    }

    @Test
    @DisplayName("身份证号全遮蔽")
    void idCardFullyMasked() {
        String result = masker.mask("身份证 320123199001011234 已登记");
        assertThat(result).doesNotContain("320123199001011234");
        assertThat(result).contains("********");
    }

    @Nested
    @DisplayName("高危凭据关键字遮蔽")
    class SecretAssignment {

        @Test
        void passwordValueMasked() {
            String result = masker.mask("password=SuperSecret123");
            assertThat(result).contains("password=********");
            assertThat(result).doesNotContain("SuperSecret123");
        }

        @Test
        void tokenColonValueMasked() {
            String result = masker.mask("token: abc123token");
            assertThat(result).contains("token=********");
            assertThat(result).doesNotContain("abc123token");
        }

        @Test
        void apiKeyWithUnderscoreMasked() {
            String result = masker.mask("api_key: sk-live-9876543210");
            assertThat(result).doesNotContain("sk-live-9876543210");
        }

        @Test
        void secretKeywordMasked() {
            String result = masker.mask("secret: s3cr3tValue");
            assertThat(result).doesNotContain("s3cr3tValue");
        }
    }

    @Test
    @DisplayName("非敏感文本保持不变")
    void nonSensitiveTextUnchanged() {
        String text = "客户申请退款需要提供订单号和退款原因。";
        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("空值与空串原样返回")
    void nullAndEmptyPassedThrough() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEqualTo("");
    }

    @Test
    @DisplayName("同时包含多种敏感信息时全部脱敏")
    void mixedSensitiveAllMasked() {
        String text = "电话 13812345678，邮箱 alice@test.com，password=Pwd123!";
        String result = masker.mask(text);
        assertThat(result).contains("138****5678");
        assertThat(result).contains("a***@test.com");
        assertThat(result).contains("password=********");
        assertThat(result).doesNotContain("Pwd123!");
    }

    @Test
    @DisplayName("containsSensitive 检测敏感信息存在")
    void containsSensitiveDetection() {
        assertThat(masker.containsSensitive("电话 13812345678")).isTrue();
        assertThat(masker.containsSensitive("password=abc")).isTrue();
        assertThat(masker.containsSensitive("普通文本没有敏感信息")).isFalse();
    }

    @Test
    @DisplayName("手机号边界不被部分数字误匹配")
    void phoneBoundaryNotOverMatched() {
        // 20 位连续数字不应被当成手机号
        String result = masker.mask("订单号 12345678901234567890");
        assertThat(result).isEqualTo("订单号 12345678901234567890");
    }
}
