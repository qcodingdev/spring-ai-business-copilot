package dev.qcoding.businesscopilot.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EnterpriseOidcUserServiceTest {
    private final EnterpriseOidcUserService service = new EnterpriseOidcUserService(new EnterpriseOidcProperties(true, null));
    private DefaultOidcUser user(String issuer, String subject, Object roles, Instant expiry) {
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                new OidcIdToken("fixture", Instant.now().minusSeconds(60), expiry,
                        Map.of("iss", issuer, "sub", subject, "email", "same@example.invalid", "business_copilot_roles", roles)),
                new OidcUserInfo(Map.of("sub", subject, "business_copilot_roles", List.of("ADMIN"))));
    }
    @Test void stableActorsUseIssuerAndSubjectAndOnlySignedApplicationRoles() {
        var first = service.mapVerifiedUser(user("https://idp.example.invalid", "user-1", List.of("OPERATOR", "GLOBAL_ADMIN"), Instant.now().plusSeconds(60)));
        var second = service.mapVerifiedUser(user("https://idp.example.invalid", "user-2", List.of("OPERATOR"), Instant.now().plusSeconds(60)));
        var anotherIssuer = service.mapVerifiedUser(user("https://another.example.invalid", "user-1", List.of("REVIEWER"), Instant.now().plusSeconds(60)));
        assertThat(first.getName()).startsWith("oidc:").hasSize(69).isNotEqualTo(second.getName()).isNotEqualTo(anotherIssuer.getName());
        assertThat(first.getName()).isEqualTo(EnterpriseOidcUserService.actorId("https://idp.example.invalid", "user-1"));
        assertThat(first.getAuthorities()).extracting("authority").containsExactly("ROLE_OPERATOR");
    }
    @Test void unassignedAndMalformedRolesFailClosedDespiteUserInfoAdminClaim() {
        for (Object claim : List.of(List.of("GLOBAL_ADMIN"), "ADMIN", List.of())) {
            assertThatThrownBy(() -> service.mapVerifiedUser(user("https://idp.example.invalid", "u", claim, Instant.now().plusSeconds(60))))
                    .isInstanceOf(OAuth2AuthenticationException.class);
        }
    }
    @Test void expiredTokenEndsActiveSessionSoRolesCannotPersistIndefinitely() throws Exception {
        var request = new MockHttpServletRequest();
        var session = (MockHttpSession) request.getSession();
        var identity = user("https://idp.example.invalid", "u", List.of("OPERATOR"), Instant.now().minusSeconds(1));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity, "", identity.getAuthorities()));
        try {
            new OidcSessionExpiryFilter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(session.isInvalid()).isTrue();
        } finally { SecurityContextHolder.clearContext(); }
    }
}
