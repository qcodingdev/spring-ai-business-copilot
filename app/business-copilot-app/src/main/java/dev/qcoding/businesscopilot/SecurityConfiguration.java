package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextFilter;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** v2.0 应用的单组织认证与角色边界配置。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            PasswordEncoder encoder,
            @Value("${business-copilot.security.admin.username:admin}") String adminUsername,
            @Value("${business-copilot.security.admin.password:admin-change-me}") String adminPassword,
            @Value("${business-copilot.security.operator.username:operator}") String operatorUsername,
            @Value("${business-copilot.security.operator.password:operator-change-me}") String operatorPassword,
            @Value("${business-copilot.security.reviewer.username:reviewer}") String reviewerUsername,
            @Value("${business-copilot.security.reviewer.password:reviewer-change-me}") String reviewerPassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername).password(encoder.encode(adminPassword)).roles("ADMIN").build(),
                User.withUsername(operatorUsername).password(encoder.encode(operatorPassword)).roles("OPERATOR").build(),
                User.withUsername(reviewerUsername).password(encoder.encode(reviewerPassword)).roles("REVIEWER").build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/error", "/favicon.ico", "/css/**", "/js/**", "/images/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/metrics/**").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/*/audit-logs").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/support-copilot/reply-drafts/*/confirm",
                                "/api/support-copilot/reply-drafts/*/edit",
                                "/api/resume-copilot/assessments/*/review",
                                "/api/resume-copilot/assessments/*/cancel")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/data-copilot/sql-candidates/*/execute",
                                "/api/support-copilot/reply-drafts/*/cancel",
                                "/api/report-copilot/reports/*/confirm",
                                "/api/report-copilot/reports/*/cancel",
                                "/api/resume-copilot/jobs/*/criteria/confirm")
                            .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.DELETE,
                                "/api/knowledge-copilot/documents/*",
                                "/api/resume-copilot/submissions/*")
                            .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().authenticated())
                // 前端从 XSRF-TOKEN Cookie 读取原始 token，并通过请求头回传。
                // Spring Security 7 默认的 XOR 处理器只接受掩码值，因此这里显式使用原始值处理器。
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                (request, response, exception) -> writeSecurityError(
                                        response, HttpStatus.UNAUTHORIZED, "SEC_0401", "请先登录"),
                                PathPatternRequestMatcher.pathPattern("/api/**"))
                        .accessDeniedHandler((request, response, exception) -> writeSecurityError(
                                response, HttpStatus.FORBIDDEN, "SEC_0403", "当前账号无权执行此操作")))
                .addFilterAfter(new BusinessRequestContextFilter(
                        request -> request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName(),
                        SecurityConfiguration::businessRoles), AnonymousAuthenticationFilter.class);

        return http.build();
    }

    private static Set<String> businessRoles(jakarta.servlet.http.HttpServletRequest request) {
        Set<String> roles = new HashSet<>();
        for (String role : new String[]{"ADMIN", "OPERATOR", "REVIEWER"}) {
            if (request.isUserInRole(role)) {
                roles.add(role);
            }
        }
        return Set.copyOf(roles);
    }

    private static void writeSecurityError(HttpServletResponse response,
                                           HttpStatus status,
                                           String errorCode,
                                           String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String requestId = BusinessRequestContextHolder.currentRequestId();
        response.getWriter().write("{\"data\":null,\"success\":false,\"errorCode\":\""
                + errorCode + "\",\"message\":\"" + message + "\",\"requestId\":"
                + (requestId == null ? "null" : "\"" + requestId + "\"")
                + ",\"timestamp\":\"" + java.time.Instant.now() + "\"}");
    }
}
