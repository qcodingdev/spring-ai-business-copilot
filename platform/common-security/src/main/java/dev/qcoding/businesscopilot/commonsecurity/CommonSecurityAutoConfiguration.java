package dev.qcoding.businesscopilot.commonsecurity;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 共享操作者、对象访问与凭证基础能力的自动配置。 */
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
