package dev.qcoding.businesscopilot.guardrails;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Auto-configuration for the ai-guardrails boundary.
 *
 * <p>自动装配 guardrails 组件：校验链、敏感字段策略、脱敏器。</p>
 */
@Configuration
public class GuardrailsAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.guardrails")
    public GuardrailsProperties guardrailsProperties() {
        return new GuardrailsProperties(null, null, null, 0, true);
    }

    @Bean
    public SensitiveFieldPolicy sensitiveFieldPolicy(GuardrailsProperties properties) {
        return new SensitiveFieldPolicy(properties);
    }

    @Bean
    public SensitiveDataMasker sensitiveDataMasker(SensitiveFieldPolicy policy) {
        return new SensitiveDataMasker(policy);
    }

    @Bean
    public SqlGuardrailService sqlGuardrailService(GuardrailsProperties properties,
                                                    SensitiveFieldPolicy sensitiveFieldPolicy) {
        List<SqlValidator> validators = List.of(
                new SingleStatementValidator(),
                new ReadOnlyStatementValidator(),
                new ForbiddenKeywordValidator(),
                new SchemaWhitelistValidator(properties.queryableTables()),
                new SensitiveFieldValidator(sensitiveFieldPolicy),
                new LimitRequiredValidator(properties.defaultMaxRows(), properties.requireLimit())
        );
        return new SqlGuardrailService(validators);
    }
}
