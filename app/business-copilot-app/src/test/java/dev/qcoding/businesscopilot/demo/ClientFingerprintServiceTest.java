package dev.qcoding.businesscopilot.demo;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ClientFingerprintServiceTest {

    private final ClientFingerprintService service = new ClientFingerprintService(
            new PublicDemoProperties(
                    20, 500, 4, "test-fingerprint-secret-at-least-32-bytes",
                    "Asia/Shanghai", Duration.ofHours(24), Duration.ofDays(7),
                    Duration.ofDays(30), BigDecimal.ZERO, BigDecimal.ZERO));

    @Test
    void ignoresCallerControlledForwardedForHeader() {
        MockHttpServletRequest first = request("198.51.100.20", "203.0.113.10");
        MockHttpServletRequest spoofed = request("198.51.100.20", "192.0.2.99, 203.0.113.10");

        assertThat(service.fingerprint(first)).isEqualTo(service.fingerprint(spoofed));
    }

    @Test
    void usesContainerResolvedRemoteAddressAndUserAgent() {
        MockHttpServletRequest first =
                request("198.51.100.20", "203.0.113.10", "test-browser");
        MockHttpServletRequest otherAddress =
                request("198.51.100.21", "203.0.113.10", "test-browser");
        MockHttpServletRequest otherAgent =
                request("198.51.100.20", "203.0.113.10", "another-browser");

        assertThat(service.fingerprint(first)).isNotEqualTo(service.fingerprint(otherAddress));
        assertThat(service.fingerprint(first)).isNotEqualTo(service.fingerprint(otherAgent));
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        return request(remoteAddress, forwardedFor, "test-browser");
    }

    private MockHttpServletRequest request(
            String remoteAddress, String forwardedFor, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        request.addHeader("User-Agent", userAgent);
        return request;
    }
}
