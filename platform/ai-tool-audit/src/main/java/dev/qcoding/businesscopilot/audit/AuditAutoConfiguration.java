package dev.qcoding.businesscopilot.audit;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the ai-tool-audit boundary.
 *
 * <p>启用审计组件扫描。JdbcQueryAuditRepository 需要 JdbcTemplate bean，
 * 由 app 模块的 Spring Boot auto-configuration 提供。</p>
 */
@Configuration
@ComponentScan
public class AuditAutoConfiguration {
}
