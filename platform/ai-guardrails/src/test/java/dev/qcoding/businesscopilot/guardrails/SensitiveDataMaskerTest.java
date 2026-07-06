package dev.qcoding.businesscopilot.guardrails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private SensitiveDataMasker masker;

    @BeforeEach
    void setUp() {
        GuardrailsProperties props = new GuardrailsProperties(null, null, null, 0, true);
        SensitiveFieldPolicy policy = new SensitiveFieldPolicy(props);
        masker = new SensitiveDataMasker(policy);
    }

    @Test
    void phoneMasked() {
        assertThat(masker.mask("phone", "13812345678")).isEqualTo("138****5678");
    }

    @Test
    void phoneShortMasked() {
        assertThat(masker.mask("phone", "1234567")).isEqualTo("****");
    }

    @Test
    void emailMasked() {
        assertThat(masker.mask("email", "user001@example.com")).isEqualTo("u***@example.com");
    }

    @Test
    void blockedColumnFullyMasked() {
        assertThat(masker.mask("password", "mysecret123")).isEqualTo("******");
        assertThat(masker.mask("token", "abc123token")).isEqualTo("******");
        assertThat(masker.mask("secret", "s3cr3t")).isEqualTo("******");
        assertThat(masker.mask("id_card", "320123199001011234")).isEqualTo("******");
    }

    @Test
    void nonSensitiveColumnUnchanged() {
        assertThat(masker.mask("name", "Alice")).isEqualTo("Alice");
        assertThat(masker.mask("total_amount", "1234.56")).isEqualTo("1234.56");
    }

    @Test
    void nullValuePassedThrough() {
        assertThat(masker.mask("phone", null)).isNull();
    }

    @Test
    void blankValuePassedThrough() {
        assertThat(masker.mask("email", "  ")).isEqualTo("  ");
    }

    @Test
    void caseInsensitiveMatching() {
        assertThat(masker.mask("PHONE", "13812345678")).isEqualTo("138****5678");
        assertThat(masker.mask("Email", "user001@example.com")).isEqualTo("u***@example.com");
    }
}
