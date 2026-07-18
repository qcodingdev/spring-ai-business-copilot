package dev.qcoding.businesscopilot;

import com.zaxxer.hikari.HikariDataSource;
import dev.qcoding.businesscopilot.datacopilot.schema.BusinessDatabaseDialect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessQueryDataSourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/platform",
                    "spring.datasource.username=platform_owner",
                    "spring.datasource.password=test-only")
            .withUserConfiguration(BusinessQueryDataSourceConfiguration.class);

    @Test
    void remainsDisabledForTheSingleDatabaseDemo() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("businessQueryDataSource");
            assertThat(context).doesNotHaveBean("businessQueryJdbcTemplate");
        });
    }

    @Test
    void createsANamedReadOnlyQueryPoolWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "business-copilot.data-copilot.datasource.enabled=true",
                        "business-copilot.data-copilot.datasource.url=jdbc:postgresql://localhost:5432/business",
                        "business-copilot.data-copilot.datasource.username=business_reader",
                        "business-copilot.data-copilot.datasource.password=test-only",
                        "business-copilot.data-copilot.datasource.maximum-pool-size=3")
                .run(context -> {
                    DataSource dataSource = context.getBean("businessQueryDataSource", DataSource.class);
                    JdbcTemplate jdbcTemplate = context.getBean("businessQueryJdbcTemplate", JdbcTemplate.class);
                    DataSource platformDataSource = context.getBean("dataSource", DataSource.class);
                    JdbcTemplate platformJdbcTemplate = context.getBean("jdbcTemplate", JdbcTemplate.class);

                    assertThat(dataSource).isInstanceOf(HikariDataSource.class);
                    HikariDataSource hikari = (HikariDataSource) dataSource;
                    assertThat(hikari.isReadOnly()).isTrue();
                    assertThat(hikari.getMaximumPoolSize()).isEqualTo(3);
                    assertThat(hikari.getPoolName()).isEqualTo("business-query-pool");
                    assertThat(hikari.getDriverClassName()).isEqualTo("org.postgresql.Driver");
                    assertThat(hikari.getConnectionInitSql())
                            .isEqualTo("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                    assertThat(context.getBean("businessQueryDatabaseDialect"))
                            .isEqualTo(BusinessDatabaseDialect.POSTGRESQL);
                    assertThat(jdbcTemplate.getDataSource()).isSameAs(dataSource);
                    assertThat(platformDataSource).isNotSameAs(dataSource);
                    assertThat(platformJdbcTemplate.getDataSource()).isSameAs(platformDataSource);
                });
    }

    @Test
    void rejectsAnEnabledDatasourceWithoutAUrl() {
        contextRunner
                .withPropertyValues("business-copilot.data-copilot.datasource.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuresMySqlReadOnlyPoolFromTheJdbcUrl() {
        contextRunner
                .withPropertyValues(
                        "business-copilot.data-copilot.datasource.enabled=true",
                        "business-copilot.data-copilot.datasource.url=jdbc:mysql://localhost:3306/business",
                        "business-copilot.data-copilot.datasource.username=business_reader",
                        "business-copilot.data-copilot.datasource.password=test-only")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource =
                            context.getBean("businessQueryDataSource", HikariDataSource.class);
                    assertThat(dataSource.isReadOnly()).isTrue();
                    assertThat(dataSource.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
                    assertThat(dataSource.getConnectionInitSql())
                            .isEqualTo("SET SESSION TRANSACTION READ ONLY");
                    assertThat(context.getBean("businessQueryDatabaseDialect"))
                            .isEqualTo(BusinessDatabaseDialect.MYSQL);
                });
    }

    @Test
    void rejectsDialectAndJdbcUrlMismatch() {
        contextRunner
                .withPropertyValues(
                        "business-copilot.data-copilot.datasource.enabled=true",
                        "business-copilot.data-copilot.datasource.url=jdbc:mysql://localhost:3306/business",
                        "business-copilot.data-copilot.datasource.username=business_reader",
                        "business-copilot.data-copilot.datasource.dialect=postgresql")
                .run(context -> assertThat(context).hasFailed());
    }
}
