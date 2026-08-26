package dev.qcoding.businesscopilot.readiness;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(EnterpriseReadinessProperties.class)
public class EnterpriseReadinessConfiguration {
}
