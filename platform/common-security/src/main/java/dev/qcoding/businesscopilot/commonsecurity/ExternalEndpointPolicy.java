package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 外部连接 SSRF 策略：协议、allowlist、DNS 后 IP、origin 和敏感 URL 均在发请求前校验。
 * DNS 重绑定仍由每次请求前重新解析和部署侧 egress policy 共同防护。
 */
public class ExternalEndpointPolicy {

    private final ExternalConnectionSecurityProperties properties;
    private final AddressResolver resolver;

    public ExternalEndpointPolicy(ExternalConnectionSecurityProperties properties) {
        this(properties, host -> List.of(InetAddress.getAllByName(host)));
    }

    ExternalEndpointPolicy(ExternalConnectionSecurityProperties properties, AddressResolver resolver) {
        this.properties = Objects.requireNonNull(properties);
        this.resolver = Objects.requireNonNull(resolver);
    }

    public URI validateBaseUrl(String value) {
        URI uri = parse(value);
        validateScheme(uri);
        if (uri.getUserInfo() != null || uri.getFragment() != null) deny("外部连接 URL 不能包含用户信息或片段。");
        if (uri.getRawQuery() != null) deny("外部连接基础 URL 不能包含查询参数。");
        String host = canonicalHost(uri);
        if (!allowed(host)) deny("外部连接域名未在 allowlist 中。");
        validateResolvedAddresses(host);
        return uri.normalize();
    }

    public URI validateRequestUrl(URI baseUri, String value) {
        URI request = parse(value).normalize();
        URI base = validateBaseUrl(baseUri.toString());
        validateScheme(request);
        if (!sameOrigin(base, request)) deny("外部连接请求不得跨 origin。");
        validateResolvedAddresses(canonicalHost(request));
        return request;
    }

    public ExternalConnectionSecurityProperties properties() {
        return properties;
    }

    private URI parse(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!uri.isAbsolute() || uri.getHost() == null) deny("外部连接 URL 必须是绝对地址。");
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部连接 URL 无效。");
        }
    }

    private void validateScheme(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) return;
        if (properties.allowHttp() && "http".equals(scheme)) return;
        deny("外部连接默认只允许 HTTPS。");
    }

    private String canonicalHost(URI uri) {
        return IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private boolean allowed(String host) {
        return properties.allowedHosts().stream().anyMatch(pattern -> {
            String normalized = IDN.toASCII(pattern.startsWith("*.") ? pattern.substring(2) : pattern,
                    IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return pattern.startsWith("*.")
                    ? host.endsWith("." + normalized) && !host.equals(normalized)
                    : host.equals(normalized);
        });
    }

    private void validateResolvedAddresses(String host) {
        final List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            deny("外部连接域名无法解析。");
            return;
        }
        if (addresses.isEmpty()) deny("外部连接域名没有可用地址。");
        if (!properties.allowPrivateAddresses() && addresses.stream().anyMatch(this::blockedAddress)) {
            deny("外部连接域名解析到受限网络地址。");
        }
    }

    private boolean blockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 198 && (second == 18 || second == 19));
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return (first & 0xfe) == 0xfc
                    || (first == 0xfe && (second & 0xc0) == 0x80);
        }
        return true;
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && canonicalHost(left).equals(canonicalHost(right))
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void deny(String message) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    @FunctionalInterface
    interface AddressResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
