package dev.qcoding.businesscopilot.commonsecurity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Issues one-time high-entropy tokens while persisting only irreversible digests. */
public class ConfirmationTokenService {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    public IssuedToken issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedToken(rawToken, digest(rawToken));
    }

    public String digest(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        return HexFormat.of().formatHex(sha256(rawToken));
    }

    public boolean matches(String rawToken, String expectedDigest) {
        if (rawToken == null || expectedDigest == null) {
            return false;
        }
        byte[] actual = digest(rawToken).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedDigest.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    private byte[] sha256(String rawToken) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record IssuedToken(String rawToken, String digest) {
    }
}
