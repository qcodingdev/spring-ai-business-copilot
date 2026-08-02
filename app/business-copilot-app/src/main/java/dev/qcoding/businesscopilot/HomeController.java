package dev.qcoding.businesscopilot;

import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the business workbench overview as the first screen.
 *
 * <p>首页控制器。第一屏是可操作的工作总览，用户再进入对应业务助手，不做营销 landing page。</p>
 */
@Controller
public class HomeController {
    /**
     * Vue Router 使用 history 模式；这些业务入口统一转发到同域打包的 SPA。
     * API、静态资源和未知文件扩展不会落入此 fallback。
     */
    @GetMapping({"/", "/login", "/admin", "/data", "/knowledge", "/support", "/report", "/hr"})
    public String spa() {
        return "forward:/index.html";
    }

    /** GET /favicon.ico：避免浏览器自动请求图标时产生无意义的 500 日志。 */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }
}
