package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.demo.RuntimeMode;
import dev.qcoding.businesscopilot.demo.RuntimeModeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;

/** 为同域 Vue 工作台提供不含敏感信息的会话、角色和运行模式摘要。 */
@RestController
public class SessionController {

    private final RuntimeModeProperties runtimeModeProperties;
    private final ObjectProvider<AiChatService> aiChatServiceProvider;

    public SessionController(RuntimeModeProperties runtimeModeProperties,
                             ObjectProvider<AiChatService> aiChatServiceProvider) {
        this.runtimeModeProperties = runtimeModeProperties;
        this.aiChatServiceProvider = aiChatServiceProvider;
    }

    /**
     * 匿名访问也会解析 {@link CsrfToken}，使登录表单和后续 SPA 写请求获得同源 XSRF Cookie。
     */
    @GetMapping("/api/session")
    public ResponseEntity<ApiResponse<SessionView>> session(
            Principal principal, HttpServletRequest request, CsrfToken csrfToken) {
        csrfToken.getToken();
        Set<String> roles = new LinkedHashSet<>();
        for (String role : new String[]{"ADMIN", "OPERATOR", "REVIEWER"}) {
            if (request.isUserInRole(role)) roles.add(role);
        }
        AiChatService ai = aiChatServiceProvider.getIfAvailable();
        RuntimeMode mode = runtimeModeProperties.mode();
        return ResponseEntity.ok(ApiResponse.ok(new SessionView(
                principal != null,
                principal == null ? null : principal.getName(),
                Set.copyOf(roles),
                mode.propertyValue(),
                mode == RuntimeMode.PUBLIC_DEMO,
                ai != null && ai.isModelEnabled())));
    }

    public record SessionView(boolean authenticated, String username, Set<String> roles,
                              String runtimeMode, boolean publicDemo, boolean aiEnabled) {
    }
}
