package dev.qcoding.businesscopilot.commonweb.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 在 HTTP 请求生命周期内建立经过校验的请求编号与操作者上下文。 */
public class BusinessRequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String ACTOR_ID_MDC_KEY = "actorId";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger log = LoggerFactory.getLogger(BusinessRequestContextFilter.class);

    private final Function<HttpServletRequest, String> actorResolver;
    private final Function<HttpServletRequest, Set<String>> rolesResolver;

    public BusinessRequestContextFilter() {
        this(BusinessRequestContextFilter::principalName, request -> Set.of());
    }

    public BusinessRequestContextFilter(Function<HttpServletRequest, String> actorResolver) {
        this(actorResolver, request -> Set.of());
    }

    public BusinessRequestContextFilter(Function<HttpServletRequest, String> actorResolver,
                                        Function<HttpServletRequest, Set<String>> rolesResolver) {
        this.actorResolver = actorResolver;
        this.rolesResolver = rolesResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        String actorId = normalizeActor(actorResolver.apply(request));
        BusinessRequestContextHolder.set(new BusinessRequestContext(
                requestId, actorId, rolesResolver.apply(request)));
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        MDC.put(ACTOR_ID_MDC_KEY, actorId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().startsWith("/api/")) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("业务请求完成：method={}，uri={}，status={}，latencyMs={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), latencyMs);
            }
            MDC.remove(ACTOR_ID_MDC_KEY);
            MDC.remove(REQUEST_ID_MDC_KEY);
            BusinessRequestContextHolder.clear();
        }
    }

    private static String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalizeActor(String actor) {
        return actor == null || actor.isBlank() ? "anonymous" : actor;
    }

    private static String principalName(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        return principal != null ? principal.getName() : null;
    }
}
