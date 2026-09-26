package dev.qcoding.businesscopilot.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;

/** Require fresh enterprise authentication after the verified ID token expires. */
public class OidcSessionExpiryFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            var expiry = user.getIdToken().getExpiresAt();
            if (expiry == null || !expiry.isAfter(Instant.now())) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
