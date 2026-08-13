package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;

/** public-demo 业务 API 默认拒绝，只开放场景入口、管理员维护和带一次性凭证的确认动作。 */
public class PublicDemoBoundaryFilter extends OncePerRequestFilter {

    private static final Pattern DEMO_SAMPLE_RESULT = Pattern.compile(
            "/api/demo/scenarios/[^/]+/sample-result");
    private static final Pattern DATA_EXECUTION = Pattern.compile(
            "/api/data-copilot/sql-candidates/[^/]+/execute");
    private static final Pattern SUPPORT_CONFIRMATION = Pattern.compile(
            "/api/support-copilot/reply-drafts/[^/]+/confirm");
    private static final Pattern REPORT_CONFIRMATION = Pattern.compile(
            "/api/report-copilot/reports/[^/]+/confirm");
    private static final Pattern RESUME_REVIEW = Pattern.compile(
            "/api/resume-copilot/assessments/[^/]+/review");
    private final RuntimeModeProperties runtimeModeProperties;

    public PublicDemoBoundaryFilter(RuntimeModeProperties runtimeModeProperties) {
        this.runtimeModeProperties = runtimeModeProperties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (runtimeModeProperties.mode() != RuntimeMode.PUBLIC_DEMO
                || isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"data":null,"success":false,"errorCode":"BIZ_0501",\
                "message":"公网体验只允许使用预置业务范例和当前结果的一次性确认操作。",\
                "requestId":%s,"timestamp":"%s"}
                """.formatted(
                jsonString(BusinessRequestContextHolder.currentRequestId()),
                Instant.now()).replace("\\\n", ""));
    }

    private boolean isAllowed(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) return true;
        if (request.isUserInRole("ADMIN") && path.startsWith("/api/admin/")) return true;
        if ("GET".equals(method)) {
            return path.equals("/api/session")
                    || path.equals("/api/demo/scenarios")
                    || path.equals("/api/demo/usage")
                    || path.equals("/api/demo/overview")
                    || DEMO_SAMPLE_RESULT.matcher(path).matches();
        }
        if (!"POST".equals(method)) return false;
        return path.equals("/api/demo/scenarios/execute")
                || DATA_EXECUTION.matcher(path).matches()
                || SUPPORT_CONFIRMATION.matcher(path).matches()
                || REPORT_CONFIRMATION.matcher(path).matches()
                || RESUME_REVIEW.matcher(path).matches();
    }

    private String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "").replace("\"", "") + "\"";
    }
}
