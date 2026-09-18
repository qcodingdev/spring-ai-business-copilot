package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextFilter;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.demo.PublicDemoBoundaryFilter;
import dev.qcoding.businesscopilot.demo.PublicDemoProperties;
import dev.qcoding.businesscopilot.demo.RuntimeModeProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

import dev.qcoding.businesscopilot.identity.EnterpriseOidcProperties;
import dev.qcoding.businesscopilot.identity.EnterpriseOidcUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 应用的单组织认证与角色边界配置。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({RuntimeModeProperties.class, PublicDemoProperties.class, EnterpriseOidcProperties.class})
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(name = "business-copilot.security.oidc.enabled", havingValue = "false", matchIfMissing = true)
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
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RuntimeModeProperties runtimeModeProperties,
            BusinessRequestContextFilter businessRequestContextFilter, EnterpriseOidcProperties oidc) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/index.html", "/error", "/favicon.ico",
                                "/assets/**", "/css/**", "/js/**", "/images/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/session").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/admin", "/admin/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/metrics/**").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/*/audit-logs").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/reviews/queue")
                            .hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/reviews/mine", "/api/reviews/subject/**")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/governance/prompts/**")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/governance/evaluations/**")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(HttpMethod.GET,
                                "/api/knowledge-copilot/quality-queue",
                                "/api/knowledge-copilot/feedback-history",
                                "/api/knowledge-copilot/quality-metrics",
                                "/api/knowledge-copilot/sources/issues",
                                "/api/support-copilot/enterprise/quality-metrics",
                                "/api/support-copilot/enterprise/quality-cases")
                            .hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.GET,
                                "/api/support-copilot/tickets/*/follow-ups",
                                "/api/support-copilot/tickets/*/handoff")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(HttpMethod.GET,
                                "/api/data-copilot/sql-candidates/*/revisions",
                                "/api/report-copilot/enterprise/drafts/*/data-trace")
                            .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.GET,
                                "/api/resume-copilot/enterprise/question-bank",
                                "/api/resume-copilot/enterprise/interview-sessions",
                                "/api/resume-copilot/enterprise/interview-sessions/*/members",
                                "/api/resume-copilot/enterprise/interview-sessions/*/summary",
                                "/api/resume-copilot/enterprise/onboarding-checklists")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(HttpMethod.GET,
                                "/api/knowledge-copilot/sources",
                                "/api/support-copilot/enterprise/connections",
                                "/api/report-copilot/enterprise/connections",
                                "/api/report-copilot/enterprise/schedules",
                                "/api/resume-copilot/enterprise/ats-connections",
                                "/api/resume-copilot/enterprise/ats-imports")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/knowledge-copilot/sources",
                                "/api/knowledge-copilot/sources/*/sync",
                                "/api/data-copilot/metrics/*/approve",
                                "/api/data-copilot/metrics/*/deactivate",
                                "/api/data-copilot/query-templates/*/approve",
                                "/api/data-copilot/query-templates/*/deactivate",
                                "/api/support-copilot/enterprise/connections",
                                "/api/support-copilot/enterprise/connections/*/import",
                                "/api/support-copilot/enterprise/sla/refresh",
                                "/api/support-copilot/enterprise/writebacks/*/resolve",
                                "/api/report-copilot/enterprise/connections",
                                "/api/report-copilot/enterprise/schedules",
                                "/api/resume-copilot/enterprise/question-bank/*/approve",
                                "/api/resume-copilot/enterprise/ats-connections",
                                "/api/resume-copilot/enterprise/ats-connections/*/import",
                                "/api/resume-copilot/enterprise/onboarding-checklists/*/approve")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/knowledge-copilot/answers/*/feedback")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/governance/prompts/versions/*/review",
                                "/api/governance/evaluations/versions/*/review",
                                "/api/reviews/*/decision",
                                "/api/knowledge-copilot/quality-queue/*/review",
                                "/api/support-copilot/enterprise/quality-cases")
                            .hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/governance/prompts/versions/*/publish",
                                "/api/governance/prompts/*/rollback",
                                "/api/governance/evaluations/datasets/*/archive",
                                "/api/governance/evaluations/versions/*/publish",
                                "/api/governance/evaluations/runs/*/external-results")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/governance/prompts/*/versions",
                                "/api/governance/prompts/definitions/*/versions",
                                "/api/governance/prompts/versions/*/submit",
                                "/api/governance/evaluations/datasets",
                                "/api/governance/evaluations/versions/*/clone",
                                "/api/governance/evaluations/versions/*/cases",
                                "/api/governance/evaluations/versions/*/cases/import",
                                "/api/governance/evaluations/versions/*/cases/*/enabled",
                                "/api/governance/evaluations/versions/*/submit",
                                "/api/governance/evaluations/runs",
                                "/api/governance/evaluations/runs/*/cancel")
                            .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/governance/prompts/versions/*")
                            .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PUT,
                                "/api/governance/evaluations/versions/*/cases/*")
                            .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PUT,
                                "/api/governance/evaluations/gate-policies/*")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/support-copilot/reply-drafts/*/confirm",
                                "/api/support-copilot/reply-drafts/*/edit",
                                "/api/support-copilot/reply-drafts/*/review-session",
                                "/api/support-copilot/reply-drafts/*/mark-customer-replied",
                                "/api/support-copilot/tickets/*/record-manual-reply",
                                "/api/resume-copilot/enterprise/interview-sessions/*/opinions",
                                "/api/resume-copilot/assessments/*/review-session",
                                "/api/resume-copilot/assessments/*/review",
                                "/api/resume-copilot/assessments/*/cancel")
                            .hasAnyRole("ADMIN", "OPERATOR", "REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/data-copilot/sql-candidates/*/execute",
                                "/api/data-copilot/query-templates/*/launch",
                                "/api/data-copilot/metrics/*/launch",
                                "/api/data-copilot/schema-change-check",
                                "/api/data-copilot/query-cost-preview",
                                "/api/data-copilot/executions/*/cancel",
                                "/api/data-copilot/query-results/*/report-handoff",
                                "/api/support-copilot/reply-drafts/*/cancel",
                                "/api/support-copilot/enterprise/drafts/*/writeback-intent",
                                "/api/support-copilot/enterprise/writebacks/*/confirm",
                                "/api/report-copilot/enterprise/reports/generate",
                                "/api/report-copilot/reports/*/confirm",
                                "/api/report-copilot/reports/*/edit",
                                "/api/report-copilot/reports/*/cancel",
                                "/api/report-copilot/reports/*/review-session",
                                "/api/resume-copilot/enterprise/consents",
                                "/api/resume-copilot/enterprise/consents/*/revoke",
                                "/api/resume-copilot/enterprise/authorized-assessments",
                                "/api/resume-copilot/enterprise/interview-sessions",
                                "/api/resume-copilot/enterprise/interview-sessions/*/members",
                                "/api/resume-copilot/enterprise/interview-sessions/*/close",
                                "/api/resume-copilot/enterprise/onboarding-instances",
                                "/api/resume-copilot/enterprise/onboarding-instances/*/tasks/*/complete",
                                "/api/resume-copilot/enterprise/onboarding-instances/*/cancel",
                                "/api/resume-copilot/jobs/*/criteria/confirm")
                            .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
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
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                (request, response, exception) -> writeSecurityError(
                                        response, HttpStatus.UNAUTHORIZED, "SEC_0401", "请先登录"),
                                PathPatternRequestMatcher.pathPattern("/api/**"))
                        .accessDeniedHandler((request, response, exception) -> writeSecurityError(
                                response, HttpStatus.FORBIDDEN, "SEC_0403", "当前账号无权执行此操作")))
                .addFilterAfter(businessRequestContextFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(new PublicDemoBoundaryFilter(runtimeModeProperties),
                        BusinessRequestContextFilter.class);

        if (oidc.enabled()) {
            http.addFilterBefore(new dev.qcoding.businesscopilot.identity.OidcSessionExpiryFilter(), AnonymousAuthenticationFilter.class);
            http.oauth2Login(login -> login.loginPage("/login").defaultSuccessUrl("/", true)
                    .failureUrl("/login?error").permitAll()
                    .userInfoEndpoint(info -> info.oidcUserService(new EnterpriseOidcUserService(oidc))));
        } else {
            http.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll());
        }
        return http.build();
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
