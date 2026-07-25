package dev.qcoding.businesscopilot.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** 将网络来源转换为每日额度使用的不可逆摘要，不持久化原始 IP。 */
@Service
public class ClientFingerprintService {

    private final byte[] secret;

    public ClientFingerprintService(PublicDemoProperties properties) {
        this.secret = properties.fingerprintSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String fingerprint(HttpServletRequest request) {
        String forwarded = firstHeaderValue(request.getHeader("X-Forwarded-For"));
        String address = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded;
        String userAgent = request.getHeader("User-Agent");
        String material = nullToEmpty(address) + "\n" + nullToEmpty(userAgent);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("当前运行环境不支持 HmacSHA256", ex);
        }
    }

    private String firstHeaderValue(String value) {
        if (value == null) return null;
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
