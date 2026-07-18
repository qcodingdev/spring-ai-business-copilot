package dev.qcoding.businesscopilot;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 生产环境仍使用演示凭证或空凭证时阻止应用启动。 */
@Component
@Profile("prod")
class ProductionConfigurationValidator implements SmartInitializingSingleton {

    private static final Map<String, String> DEMO_CREDENTIALS = Map.of(
            "平台数据库密码", "copilot",
            "管理员密码", "admin-change-me",
            "操作员密码", "operator-change-me",
            "审计员密码", "reviewer-change-me",
            "业务查询数据库密码", "business-reader-change-me");

    private final Map<String, String> configuredCredentials = new LinkedHashMap<>();
    private final boolean businessQueryDataSourceEnabled;

    ProductionConfigurationValidator(
            @Value("${spring.datasource.password:}") String platformDatabasePassword,
            @Value("${business-copilot.security.admin.password:}") String adminPassword,
            @Value("${business-copilot.security.operator.password:}") String operatorPassword,
            @Value("${business-copilot.security.reviewer.password:}") String reviewerPassword,
            @Value("${business-copilot.data-copilot.datasource.enabled:false}") boolean businessQueryDataSourceEnabled,
            @Value("${business-copilot.data-copilot.datasource.password:}") String businessQueryDatabasePassword) {
        configuredCredentials.put("平台数据库密码", platformDatabasePassword);
        configuredCredentials.put("管理员密码", adminPassword);
        configuredCredentials.put("操作员密码", operatorPassword);
        configuredCredentials.put("审计员密码", reviewerPassword);
        configuredCredentials.put("业务查询数据库密码", businessQueryDatabasePassword);
        this.businessQueryDataSourceEnabled = businessQueryDataSourceEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!businessQueryDataSourceEnabled) {
            throw new IllegalStateException("生产环境必须启用独立只读业务查询数据源");
        }
        configuredCredentials.forEach((name, value) -> {
            if (name.equals("业务查询数据库密码") && !businessQueryDataSourceEnabled) {
                return;
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("生产环境必须显式配置" + name);
            }
            if (value.equals(DEMO_CREDENTIALS.get(name))) {
                throw new IllegalStateException("生产环境禁止使用演示" + name);
            }
        });
    }
}
