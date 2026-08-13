package dev.qcoding.businesscopilot.commonweb;

import dev.qcoding.businesscopilot.commonweb.exception.GlobalExceptionHandler;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

class CommonWebAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class));

    @Test
    void registersHostIndependentWebBoundary() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(BusinessRequestContextFilter.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(FilterRegistrationBean.class).isEnabled()).isTrue();
        });
    }

    @Test
    void hostCanDisableServletRegistrationWhenFilterLivesInSecurityChain() {
        runner.withPropertyValues(
                        "business-copilot.common-web.register-request-context-filter=false")
                .run(context -> assertThat(
                        context.getBean(FilterRegistrationBean.class).isEnabled()).isFalse());
    }
}
