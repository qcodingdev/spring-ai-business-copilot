package dev.qcoding.businesscopilot.commonsecurity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationTokenServiceTest {

    private final ConfirmationTokenService service = new ConfirmationTokenService();

    @Test
    void issuesHighEntropyTokenAndDigest() {
        ConfirmationTokenService.IssuedToken token = service.issue();
        assertThat(token.rawToken()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(token.digest()).hasSize(64).doesNotContain(token.rawToken());
        assertThat(service.matches(token.rawToken(), token.digest())).isTrue();
    }

    @Test
    void rejectsWrongOrMissingToken() {
        ConfirmationTokenService.IssuedToken token = service.issue();
        assertThat(service.matches("wrong", token.digest())).isFalse();
        assertThat(service.matches(null, token.digest())).isFalse();
        assertThat(service.matches(token.rawToken(), null)).isFalse();
    }
}
