package dev.qcoding.businesscopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI Business Copilot application entry point.
 *
 * <p>应用启动入口。当前统一装配 Data、Knowledge、Support、Report 和 Resume 五个 Copilot。</p>
 */
@SpringBootApplication
public class BusinessCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessCopilotApplication.class, args);
    }
}
