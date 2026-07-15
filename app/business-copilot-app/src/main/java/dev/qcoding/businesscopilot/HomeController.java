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

    /** GET / — Data Copilot 工作台 */
    @GetMapping("/")
    public String index(Principal principal, Model model) {
        if (principal != null) {
            model.addAttribute("currentUser", principal.getName());
        }
        return "index";
    }

    /** GET /login — role-based workbench sign-in page. */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** GET /favicon.ico — avoid noisy 500s for the browser's automatic favicon request. */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }
}
