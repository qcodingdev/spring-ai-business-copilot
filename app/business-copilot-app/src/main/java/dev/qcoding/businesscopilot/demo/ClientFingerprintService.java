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
        /*
         * public-demo 使用容器原生的可信代理解析，getRemoteAddr() 已是校验后的客户端地址。
         * 这里不能再次读取原始 X-Forwarded-For，否则调用方可以伪造首段地址绕过每日额度。
         */
        String address = request.getRemoteAddr();
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
