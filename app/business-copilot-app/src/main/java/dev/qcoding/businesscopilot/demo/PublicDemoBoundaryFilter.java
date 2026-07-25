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
import java.util.Set;

/** public-demo 禁止绕过场景目录上传资料或直接调用原始 AI 输入端点。 */
public class PublicDemoBoundaryFilter extends OncePerRequestFilter {

    private static final Set<String> DIRECT_AI_POSTS = Set.of(
            "/api/data-copilot/sql-candidates",
            "/api/knowledge-copilot/questions",
            "/api/support-copilot/tickets/analyze",
            "/api/report-copilot/reports/generate",
            "/api/resume-copilot/jobs/draft",
            "/api/resume-copilot/jobs/criteria",
            "/api/resume-copilot/assessments");
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
                || !isForbidden(request.getMethod(), request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"data":null,"success":false,"errorCode":"BIZ_0501",\
                "message":"公网体验必须从预置业务范例进入，不能上传或提交真实资料。",\
                "requestId":%s,"timestamp":"%s"}
                """.formatted(
                jsonString(BusinessRequestContextHolder.currentRequestId()),
                Instant.now()).replace("\\\n", ""));
    }

    private boolean isForbidden(String method, String path) {
        if (path == null || !path.startsWith("/api/")) return false;
        if ("GET".equals(method) && (path.equals("/api/data-copilot/schema")
                || path.equals("/api/report-copilot/sample-sources")
                || path.startsWith("/api/knowledge-copilot/index-jobs/")
                || path.matches("/api/resume-copilot/assessments/[^/]+/review")
                || path.endsWith("/audit-logs"))) return true;
        if ("GET".equals(method) && path.equals("/api/knowledge-copilot/documents")) return true;
        if (path.startsWith("/api/knowledge-copilot/documents")) return true;
        if (path.startsWith("/api/report-copilot/source-")
                || path.equals("/api/report-copilot/reports/generate-from-file")) return true;
        if (path.endsWith("/file") && path.startsWith("/api/resume-copilot/")) return true;
        if ("PUT".equals(method) && path.startsWith("/api/resume-copilot/jobs/")
                && path.endsWith("/criteria")) return true;
        if ("DELETE".equals(method) && path.startsWith("/api/resume-copilot/submissions/")) return true;
        return "POST".equals(method) && DIRECT_AI_POSTS.contains(path);
    }

    private String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "").replace("\"", "") + "\"";
    }
}
