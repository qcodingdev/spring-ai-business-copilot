package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.demo.RuntimeModeProperties;
import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;

/**
 * Serves the business workbench overview as the first screen.
 *
 * <p>首页控制器。第一屏是可操作的工作总览，用户再进入对应业务助手，不做营销 landing page。</p>
 */
@Controller
public class HomeController {

    private final RuntimeModeProperties runtimeModeProperties;

    public HomeController(RuntimeModeProperties runtimeModeProperties) {
        this.runtimeModeProperties = runtimeModeProperties;
    }

    /** GET /：业务助手工作台。 */
    @GetMapping("/")
    public String index(Principal principal, Model model, HttpServletRequest request) {
        if (principal != null) {
            model.addAttribute("currentUser", principal.getName());
        }
        model.addAttribute("runtimeMode", runtimeModeProperties.mode().propertyValue());
        model.addAttribute("publicDemo",
                runtimeModeProperties.mode() == dev.qcoding.businesscopilot.demo.RuntimeMode.PUBLIC_DEMO);
        model.addAttribute("adminUser", request.isUserInRole("ADMIN"));
        return "index";
    }

    /** GET /login：按角色登录工作台。 */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** GET /admin：仅管理员可见的只读诊断和虚构数据维护页。 */
    @GetMapping("/admin")
    public String admin(Model model, Principal principal) {
        model.addAttribute("currentUser", principal == null ? "admin" : principal.getName());
        model.addAttribute("runtimeMode", runtimeModeProperties.mode().propertyValue());
        return "admin";
    }

    /** GET /favicon.ico：避免浏览器自动请求图标时产生无意义的 500 日志。 */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }
}
