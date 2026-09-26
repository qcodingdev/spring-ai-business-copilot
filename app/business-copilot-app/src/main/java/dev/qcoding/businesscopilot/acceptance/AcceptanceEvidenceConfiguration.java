package dev.qcoding.businesscopilot.acceptance;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** 分层验收证据装配（CORE-05）。 */
@Configuration(proxyBeanMethods = false)
public class AcceptanceEvidenceConfiguration {

    @Bean
    public AcceptanceEvidenceService acceptanceEvidenceService(
            @Qualifier("jdbcTemplate") JdbcTemplate platformJdbcTemplate,
            dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider actorProvider,
            dev.qcoding.businesscopilot.readiness.EnterpriseReadinessProperties properties) {
        return new AcceptanceEvidenceService(platformJdbcTemplate, actorProvider, properties.applicationVersion());
    }
}
