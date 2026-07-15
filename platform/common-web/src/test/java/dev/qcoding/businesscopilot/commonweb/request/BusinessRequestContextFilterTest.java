package dev.qcoding.businesscopilot.commonweb.request;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
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
        FilterChain chain = (req, res) -> observed.set(BusinessRequestContextHolder.current());

        new BusinessRequestContextFilter().doFilter(request, response, chain);

        assertThat(observed.get()).isEqualTo(new BusinessRequestContext("request-1234", "operator"));
        assertThat(response.getHeader(BusinessRequestContextFilter.REQUEST_ID_HEADER)).isEqualTo("request-1234");
        assertThat(BusinessRequestContextHolder.current()).isNull();
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
}
