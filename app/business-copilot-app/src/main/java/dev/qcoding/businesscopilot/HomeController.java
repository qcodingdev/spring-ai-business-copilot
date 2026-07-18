package dev.qcoding.businesscopilot;

import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

/**
 * Serves the Data Copilot workbench as the first screen.
 *
 * <p>首页控制器。第一屏就是可用的 Data Copilot 工作台，不做营销 landing page。</p>
 */
@Controller
public class HomeController {

    /** GET /：业务助手工作台。 */
    @GetMapping("/")
    public String index(Principal principal, Model model) {
        if (principal != null) {
            model.addAttribute("currentUser", principal.getName());
        }
        return "index";
    }

    /** GET /login：按角色登录工作台。 */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** GET /favicon.ico：避免浏览器自动请求图标时产生无意义的 500 日志。 */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }
}
