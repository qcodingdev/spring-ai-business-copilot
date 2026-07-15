package dev.qcoding.businesscopilot;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fails production startup when demo or empty credentials are still configured. */
@Component
@Profile("prod")
class ProductionConfigurationValidator implements SmartInitializingSingleton {

    private static final Map<String, String> DEMO_CREDENTIALS = Map.of(
            "platform database password", "copilot",
            "admin password", "admin-change-me",
            "operator password", "operator-change-me",
            "reviewer password", "reviewer-change-me",
            "business query password", "business-reader-change-me");

    private final Map<String, String> configuredCredentials = new LinkedHashMap<>();
    private final boolean businessQueryDataSourceEnabled;

    ProductionConfigurationValidator(
            @Value("${spring.datasource.password:}") String platformDatabasePassword,
            @Value("${business-copilot.security.admin.password:}") String adminPassword,
            @Value("${business-copilot.security.operator.password:}") String operatorPassword,
            @Value("${business-copilot.security.reviewer.password:}") String reviewerPassword,
            @Value("${business-copilot.data-copilot.datasource.enabled:false}") boolean businessQueryDataSourceEnabled,
            @Value("${business-copilot.data-copilot.datasource.password:}") String businessQueryDatabasePassword) {
        configuredCredentials.put("platform database password", platformDatabasePassword);
        configuredCredentials.put("admin password", adminPassword);
        configuredCredentials.put("operator password", operatorPassword);
        configuredCredentials.put("reviewer password", reviewerPassword);
        configuredCredentials.put("business query password", businessQueryDatabasePassword);
        this.businessQueryDataSourceEnabled = businessQueryDataSourceEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        configuredCredentials.forEach((name, value) -> {
            if (name.equals("business query password") && !businessQueryDataSourceEnabled) {
                return;
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Production profile requires an explicit " + name);
            }
            if (value.equals(DEMO_CREDENTIALS.get(name))) {
                throw new IllegalStateException("Production profile rejects the demo " + name);
            }
        });
    }
}
