package dev.qcoding.businesscopilot.commonsecurity;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 共享操作者、对象访问与凭证基础能力的自动配置。 */
@AutoConfiguration
@EnableConfigurationProperties
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

    @Bean
    @ConditionalOnMissingBean
    public ExternalSecretResolver externalSecretResolver(Environment environment) {
        return new EnvironmentExternalSecretResolver(environment);
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.external-security")
    public ExternalConnectionSecurityProperties externalConnectionSecurityProperties() {
        return new ExternalConnectionSecurityProperties(
                java.util.List.of(), false, false, null, null, null, 0, 0, 0, 0);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExternalEndpointPolicy externalEndpointPolicy(ExternalConnectionSecurityProperties properties) {
        return new ExternalEndpointPolicy(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExternalHttpClientFactory externalHttpClientFactory(ExternalEndpointPolicy endpointPolicy) {
        return new ExternalHttpClientFactory(endpointPolicy);
    }
}
