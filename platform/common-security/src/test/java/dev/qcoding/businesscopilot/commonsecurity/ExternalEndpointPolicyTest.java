package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalEndpointPolicyTest {

    private final ExternalConnectionSecurityProperties properties =
            new ExternalConnectionSecurityProperties(
                    List.of("api.example.com", "*.trusted.example"),
                    false, false, Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(10), 1024, 2, 50, 16);

    @Test
    void acceptsHttpsAllowlistedPublicAddress() throws Exception {
        ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties,
                host -> List.of(InetAddress.getByName("203.0.113.20")));
        assertThat(policy.validateBaseUrl("https://api.example.com/v1").getHost())
                .isEqualTo("api.example.com");
        assertThat(policy.validateBaseUrl("https://tenant.trusted.example").getHost())
                .isEqualTo("tenant.trusted.example");
    }

    @Test
    void rejectsHttpCredentialsQueriesAndUnknownHosts() throws Exception {
        ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties,
                host -> List.of(InetAddress.getByName("203.0.113.20")));
        assertThatThrownBy(() -> policy.validateBaseUrl("http://api.example.com"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateBaseUrl("https://user:secret@api.example.com"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateBaseUrl("https://api.example.com?token=secret"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateBaseUrl("https://evil.example.net"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalMetadataAndMixedDns() throws Exception {
        for (String blocked : List.of(
                "127.0.0.1", "10.1.2.3", "169.254.169.254", "224.0.0.1", "::1", "fd00::1")) {
            ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties,
                    host -> List.of(InetAddress.getByName(blocked)));
            assertThatThrownBy(() -> policy.validateBaseUrl("https://api.example.com"))
                    .as(blocked).isInstanceOf(BusinessException.class);
        }
        ExternalEndpointPolicy mixed = new ExternalEndpointPolicy(properties,
                host -> List.of(InetAddress.getByName("203.0.113.20"), InetAddress.getByName("127.0.0.1")));
        assertThatThrownBy(() -> mixed.validateBaseUrl("https://api.example.com"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsCrossOriginRequestAndRevalidatesSameOrigin() throws Exception {
        ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties,
                host -> List.of(InetAddress.getByName("203.0.113.20")));
        var base = policy.validateBaseUrl("https://api.example.com/v1");
        assertThat(policy.validateRequestUrl(base, "https://api.example.com/v1/items").getPath())
                .isEqualTo("/v1/items");
        assertThatThrownBy(() -> policy.validateRequestUrl(base, "https://tenant.trusted.example/items"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsOversizedOrDeepJsonPayloads() {
        ExternalEndpointPolicy policy = new ExternalEndpointPolicy(properties,
                host -> List.of());
        ExternalHttpClientFactory factory = new ExternalHttpClientFactory(policy);
        ObjectMapper mapper = new ObjectMapper();

        assertThat(factory.validatePayload(mapper.valueToTree(
                java.util.Map.of("items", List.of("a", "b"))))).isNotNull();
        assertThatThrownBy(() -> factory.validatePayload(
                mapper.valueToTree(java.util.Map.of("items",
                        java.util.stream.IntStream.range(0, 51).boxed().toList()))))
                .isInstanceOf(BusinessException.class);

        Object nested = "leaf";
        for (int index = 0; index < 17; index++) nested = java.util.Map.of("child", nested);
        Object tooDeep = nested;
        assertThatThrownBy(() -> factory.validatePayload(mapper.valueToTree(tooDeep)))
                .isInstanceOf(BusinessException.class);
    }
}
