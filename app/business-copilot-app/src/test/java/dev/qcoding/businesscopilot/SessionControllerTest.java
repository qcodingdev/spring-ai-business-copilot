package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.demo.RuntimeModeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionControllerTest {

    @Test
    void returnsAnonymousSessionAndInitializesCsrfCookie() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiChatService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CsrfToken csrfToken = mock(CsrfToken.class);
        when(csrfToken.getToken()).thenReturn("csrf");
        SessionController controller = new SessionController(
                new RuntimeModeProperties("public-demo"), provider);

        ApiResponse<SessionController.SessionView> body = controller.session(
                null, new MockHttpServletRequest(), csrfToken).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data().authenticated()).isFalse();
        assertThat(body.data().runtimeMode()).isEqualTo("public-demo");
        assertThat(body.data().publicDemo()).isTrue();
        assertThat(body.data().aiEnabled()).isFalse();
        assertThat(body.data().roles()).isEmpty();
        verify(csrfToken).getToken();
    }

    @Test
    void returnsAuthenticatedRolesWithoutProviderSecrets() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiChatService> provider = mock(ObjectProvider.class);
        AiChatService ai = mock(AiChatService.class);
        when(ai.isModelEnabled()).thenReturn(true);
        when(provider.getIfAvailable()).thenReturn(ai);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addUserRole("ADMIN");
        request.addUserRole("REVIEWER");
        Principal principal = () -> "fictional-admin";
        CsrfToken csrfToken = mock(CsrfToken.class);
        SessionController controller = new SessionController(
                new RuntimeModeProperties("self-hosted"), provider);

        ApiResponse<SessionController.SessionView> body = controller.session(
                principal, request, csrfToken).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data().username()).isEqualTo("fictional-admin");
        assertThat(body.data().roles()).containsExactlyInAnyOrder("ADMIN", "REVIEWER");
        assertThat(body.data().runtimeMode()).isEqualTo("self-hosted");
        assertThat(body.data().aiEnabled()).isTrue();
    }
}
