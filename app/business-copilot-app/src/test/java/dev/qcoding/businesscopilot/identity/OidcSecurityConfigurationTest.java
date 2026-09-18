package dev.qcoding.businesscopilot.identity;

import dev.qcoding.businesscopilot.HomeController;
import dev.qcoding.businesscopilot.SecurityConfiguration;
import dev.qcoding.businesscopilot.commonweb.CommonWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HomeController.class, properties = "business-copilot.security.oidc.enabled=true")
@Import({SecurityConfiguration.class, CommonWebAutoConfiguration.class, OidcSecurityConfigurationTest.Client.class})
class OidcSecurityConfigurationTest {
    @Autowired ApplicationContext context;
    @Autowired SecurityFilterChain chain;
    @Autowired MockMvc mvc;
    @Test void enterpriseModeHasNoLocalSampleAccountsOrPasswordLoginFilter() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        assertThat(chain.getFilters()).anyMatch(OAuth2LoginAuthenticationFilter.class::isInstance)
                .noneMatch(UsernamePasswordAuthenticationFilter.class::isInstance);
    }
    @Test void authorizationEntryUsesConfiguredClientAndStateWithoutContactingIdp() throws Exception {
        mvc.perform(get("/oauth2/authorization/enterprise"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("https://idp.example.invalid/authorize?"),
                        org.hamcrest.Matchers.containsString("client_id=fixture-client"),
                        org.hamcrest.Matchers.containsString("state="), org.hamcrest.Matchers.containsString("nonce="))));
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Client {
        @Bean ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("enterprise")
                    .clientId("fixture-client").clientSecret("fixture-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .scope("openid", "profile").redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("https://idp.example.invalid/authorize")
                    .tokenUri("https://idp.example.invalid/token").jwkSetUri("https://idp.example.invalid/jwks")
                    .issuerUri("https://idp.example.invalid").build());
        }
    }
}
