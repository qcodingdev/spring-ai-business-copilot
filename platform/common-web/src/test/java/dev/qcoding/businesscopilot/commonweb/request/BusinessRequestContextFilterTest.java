package dev.qcoding.businesscopilot.commonweb.request;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRequestContextFilterTest {

    @Test
    void preservesSafeRequestIdAndExposesAuthenticatedActorDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BusinessRequestContextFilter.REQUEST_ID_HEADER, "request-1234");
        request.setUserPrincipal((Principal) () -> "operator");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<BusinessRequestContext> observed = new AtomicReference<>();
        AtomicReference<String> observedRequestId = new AtomicReference<>();
        AtomicReference<String> observedActorId = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            observed.set(BusinessRequestContextHolder.current());
            observedRequestId.set(MDC.get(BusinessRequestContextFilter.REQUEST_ID_MDC_KEY));
            observedActorId.set(MDC.get(BusinessRequestContextFilter.ACTOR_ID_MDC_KEY));
        };

        new BusinessRequestContextFilter().doFilter(request, response, chain);

        assertThat(observed.get()).isEqualTo(new BusinessRequestContext("request-1234", "operator"));
        assertThat(observedRequestId.get()).isEqualTo("request-1234");
        assertThat(observedActorId.get()).isEqualTo("operator");
        assertThat(response.getHeader(BusinessRequestContextFilter.REQUEST_ID_HEADER)).isEqualTo("request-1234");
        assertThat(BusinessRequestContextHolder.current()).isNull();
        assertThat(MDC.get(BusinessRequestContextFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(BusinessRequestContextFilter.ACTOR_ID_MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BusinessRequestContextFilter.REQUEST_ID_HEADER, "bad id\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        new BusinessRequestContextFilter().doFilter(request, response,
                (req, res) -> observed.set(BusinessRequestContextHolder.currentRequestId()));

        assertThat(observed.get()).hasSize(32).matches("[a-f0-9]{32}");
    }

    @Test
    void acceptsOnlyExplicitEnglishAndDefaultsEveryOtherLanguageToChinese() throws Exception {
        assertThat(BusinessRequestContextFilter.resolveLocale(null)).isEqualTo("zh-CN");
        assertThat(BusinessRequestContextFilter.resolveLocale("en-US")).isEqualTo("en-US");
        assertThat(BusinessRequestContextFilter.resolveLocale("en-US,en;q=0.9")).isEqualTo("en-US");
        assertThat(BusinessRequestContextFilter.resolveLocale("en-GB")).isEqualTo("zh-CN");
        assertThat(BusinessRequestContextFilter.resolveLocale("fr-FR")).isEqualTo("zh-CN");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        new BusinessRequestContextFilter().doFilter(request, response,
                (req, res) -> observed.set(BusinessRequestContextHolder.currentLocale()));
        assertThat(observed.get()).isEqualTo("en-US");
        assertThat(MDC.get(BusinessRequestContextFilter.LOCALE_MDC_KEY)).isNull();
    }
}
