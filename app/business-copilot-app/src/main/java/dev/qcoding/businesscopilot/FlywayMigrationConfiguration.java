package dev.qcoding.businesscopilot;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Runs application database migrations explicitly at startup.
 *
 * <p>Spring Boot 4 keeps Flyway integration outside the core auto-configuration set used here,
 * so the app owns migration startup instead of relying on implicit auto-detection.</p>
 */
@Configuration
public class FlywayMigrationConfiguration {

    @Bean
    public Flyway flyway(DataSource dataSource,
                         @Value("${spring.flyway.enabled:true}") boolean enabled,
                         @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
                         @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .baselineOnMigrate(baselineOnMigrate)
                .load();
        if (enabled) {
            flyway.migrate();
        }
        return flyway;
    }
}
