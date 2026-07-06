package dev.qcoding.businesscopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI Business Copilot application entry point.
 *
 * <p>应用启动入口。第一版业务能力在 Data Copilot 模块中逐步接入。</p>
 */
@SpringBootApplication
public class BusinessCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessCopilotApplication.class, args);
    }
}
