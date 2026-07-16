package dev.qcoding.businesscopilot.commonsecurity;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for shared actor, object access, and token primitives. */
@AutoConfiguration
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CurrentActorProvider currentActorProvider() {
        return new RequestContextCurrentActorProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectAccessPolicy objectAccessPolicy() {
        return new DefaultObjectAccessPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfirmationTokenService confirmationTokenService() {
        return new ConfirmationTokenService();
    }
}
