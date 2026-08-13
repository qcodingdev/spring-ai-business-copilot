package dev.qcoding.businesscopilot;

import com.zaxxer.hikari.HikariDataSource;
import dev.qcoding.businesscopilot.datacopilot.query.JdbcReadOnlyQueryExecutor;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionProperties;
import dev.qcoding.businesscopilot.datacopilot.schema.BusinessDatabaseDialect;
import dev.qcoding.businesscopilot.datacopilot.schema.DataCopilotSchemaProperties;
import dev.qcoding.businesscopilot.datacopilot.schema.JdbcSchemaMetadataRepository;
import dev.qcoding.businesscopilot.guardrails.GuardrailsAutoConfiguration;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SensitiveDataMasker;
import dev.qcoding.businesscopilot.guardrails.SensitiveFieldPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
class MySqlBusinessQueryIntegrationTest {

    private static final String DATABASE = "business_target";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            DockerImageName.parse(System.getProperty(
                    "business-copilot.test.mysql-image", "mysql:8.4")))
            .withDatabaseName(DATABASE)
            .withUsername("root")
            .withPassword("root-test");

    @BeforeAll
    static void prepareReaderAndSampleTable() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        admin.execute("""
                CREATE TABLE customers (
                    id BIGINT PRIMARY KEY,
                    email VARCHAR(200) COMMENT 'masked contact email',
                    display_name VARCHAR(200) COMMENT 'customer display name'
                )
                """);
        admin.update("INSERT INTO customers (id, email, display_name) VALUES (?, ?, ?)",
                1L, "user@example.com", "Sample Customer");
        admin.execute("CREATE USER 'business_reader'@'%' IDENTIFIED BY 'reader-test'");
        admin.execute("GRANT SELECT ON `" + DATABASE + "`.`customers` TO 'business_reader'@'%'");
    }

    @Test
    void discoversMySqlSchemaAndExecutesThroughTheReadOnlyBoundary() {
        BusinessQueryDataSourceProperties properties = new BusinessQueryDataSourceProperties();
        properties.setEnabled(true);
        properties.setUrl(MYSQL.getJdbcUrl());
        properties.setUsername("business_reader");
        properties.setPassword("reader-test");
        properties.setDialect("auto");

        BusinessQueryDataSourceConfiguration configuration =
                new BusinessQueryDataSourceConfiguration();
        BusinessDatabaseDialect dialect =
                configuration.businessQueryDatabaseDialect(properties);
        HikariDataSource dataSource = (HikariDataSource)
                configuration.businessQueryDataSource(properties, dialect);

        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            DataCopilotSchemaProperties schemaProperties = new DataCopilotSchemaProperties(
                    List.of(DATABASE + ".customers"),
                    Map.of(),
                    Map.of(DATABASE + ".customers.email", "mask"),
                    2000);
            JdbcSchemaMetadataRepository metadataRepository =
                    new JdbcSchemaMetadataRepository(jdbcTemplate, schemaProperties, dialect);

            assertThat(metadataRepository.findQueryableTableNames())
                    .containsExactly(DATABASE + ".customers");
            assertThat(metadataRepository.findColumns(DATABASE + ".customers"))
                    .extracting(column -> column.name())
                    .containsExactly("id", "email", "display_name");

            GuardrailsProperties guardrails = new GuardrailsProperties(
                    List.of(DATABASE + ".customers"),
                    List.of(DATABASE + ".customers.id",
                            DATABASE + ".customers.email",
                            DATABASE + ".customers.display_name"),
                    List.of("password", "token", "secret", "id_card"),
                    List.of("email"),
                    100, true, List.of("count", "sum", "avg", "min", "max"));
            GuardrailsAutoConfiguration guardrailsConfiguration =
                    new GuardrailsAutoConfiguration();
            SensitiveFieldPolicy sensitiveFieldPolicy =
                    guardrailsConfiguration.sensitiveFieldPolicy(guardrails);
            SensitiveDataMasker masker =
                    guardrailsConfiguration.sensitiveDataMasker(sensitiveFieldPolicy);
            JdbcReadOnlyQueryExecutor executor = new JdbcReadOnlyQueryExecutor(
                    jdbcTemplate,
                    guardrailsConfiguration.sqlGuardrailService(guardrails, sensitiveFieldPolicy),
                    guardrails,
                    masker,
                    new QueryExecutionProperties(5, 10, 10, 10, 65536));

            var result = executor.execute(
                    "SELECT id, email, display_name FROM " + DATABASE + ".customers LIMIT 10");
            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.rows().getFirst().values().get("email"))
                    .isEqualTo("u***@example.com");
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE " + DATABASE + ".customers SET display_name = 'Changed' WHERE id = 1"))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            dataSource.close();
        }
    }
}
