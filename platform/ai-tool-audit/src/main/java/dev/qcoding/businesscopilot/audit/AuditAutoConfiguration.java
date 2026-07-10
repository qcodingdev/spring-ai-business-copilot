package dev.qcoding.businesscopilot.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for the ai-tool-audit boundary.
 *
 * <p>显式注册审计组件。JdbcQueryAuditRepository 需要 JdbcTemplate bean，
 * 由 app 模块的 Spring Boot auto-configuration 提供。</p>
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnBean(JdbcTemplate.class)
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QueryAuditRepository queryAuditRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcQueryAuditRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditService auditService(QueryAuditRepository repository) {
        return new AuditService(repository);
    }
}
