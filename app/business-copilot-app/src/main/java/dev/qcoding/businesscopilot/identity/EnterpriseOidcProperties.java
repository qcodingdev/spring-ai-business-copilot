package dev.qcoding.businesscopilot.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** One trusted enterprise issuer; local sample accounts are disabled when enabled. */
@ConfigurationProperties("business-copilot.security.oidc")
public record EnterpriseOidcProperties(boolean enabled, String roleClaim) {
    public EnterpriseOidcProperties {
        roleClaim = roleClaim == null || roleClaim.isBlank() ? "business_copilot_roles" : roleClaim;
    }
}
