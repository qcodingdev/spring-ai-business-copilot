package dev.qcoding.businesscopilot.commonweb;

import dev.qcoding.businesscopilot.commonweb.exception.GlobalExceptionHandler;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Host-independent exception and authenticated request-context integration. */
@AutoConfiguration
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public BusinessRequestContextFilter businessRequestContextFilter() {
        return new BusinessRequestContextFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "businessRequestContextFilterRegistration")
    public FilterRegistrationBean<BusinessRequestContextFilter> businessRequestContextFilterRegistration(
            BusinessRequestContextFilter filter,
            @Value("${business-copilot.common-web.register-request-context-filter:true}")
            boolean enabled) {
        FilterRegistrationBean<BusinessRequestContextFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName("businessRequestContextFilter");
        registration.setEnabled(enabled);
        // Run downstream of an authentication filter while still wrapping controller execution.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
