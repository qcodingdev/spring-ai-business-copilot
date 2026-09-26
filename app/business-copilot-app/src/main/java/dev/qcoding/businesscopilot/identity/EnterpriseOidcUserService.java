package dev.qcoding.businesscopilot.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/** Maps signed, application-specific ID token roles onto the existing three business roles. */
public class EnterpriseOidcUserService extends OidcUserService {
    private final EnterpriseOidcProperties properties;
    public EnterpriseOidcUserService(EnterpriseOidcProperties properties) { this.properties = properties; }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        if (!"enterprise".equals(request.getClientRegistration().getRegistrationId())) throw denied();
        // Spring Security verifies the signature, issuer, audience and nonce before this callback.
        OidcUser verified = super.loadUser(request);
        return mapVerifiedUser(verified);
    }

    OidcUser mapVerifiedUser(OidcUser user) {
        var token = user.getIdToken();
        if (token.getIssuer() == null || token.getSubject() == null || token.getSubject().isBlank()) throw denied();
        Object claim = token.getClaims().get(properties.roleClaim());
        if (!(claim instanceof Collection<?> roles)) throw denied();
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String name && Set.of("ADMIN", "OPERATOR", "REVIEWER").contains(name)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + name));
            }
        }
        if (authorities.isEmpty()) throw denied();
        String actor = actorId(token.getIssuer().toString(), token.getSubject());
        return new DefaultOidcUser(authorities, token, user.getUserInfo()) {
            @Override public String getName() { return actor; }
        };
    }

    static String actorId(String issuer, String subject) {
        try {
            byte[] bytes = (issuer + "\n" + subject).getBytes(StandardCharsets.UTF_8);
            return "oidc:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static OAuth2AuthenticationException denied() {
        return new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "企业身份未分配业务角色");
    }
}
