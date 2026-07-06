package dev.qcoding.businesscopilot.datacopilot.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaContextServiceTest {

    private SchemaMetadataRepository repository;
    private DataCopilotSchemaProperties properties;
    private SchemaContextService service;

    @BeforeEach
    void setUp() {
        repository = mock(SchemaMetadataRepository.class);
        properties = new DataCopilotSchemaProperties(
                List.of("customers"),
                Map.of("customers.id", "customer primary key", "customers", "客户表"),
                Map.of("customers.password", "block", "customers.phone", "mask"),
                1000);
        service = new SchemaContextService(repository, properties);
    }

    @Test
    void buildsContextFromWhitelistedTables() {
        when(repository.findQueryableTableNames()).thenReturn(List.of("customers"));
        when(repository.findColumns("customers")).thenReturn(List.of(
                new ColumnSchema("id", "bigint", false, "customer primary key", false, null),
                new ColumnSchema("phone", "varchar", true, "phone", true, "mask"),
                new ColumnSchema("password", "varchar", false, "password", true, "block")));

        SchemaContext context = service.buildContext();

        assertThat(context.tables()).hasSize(1);
        TableSchema table = context.tables().get(0);
        assertThat(table.name()).isEqualTo("customers");
        assertThat(table.columns()).hasSize(3);
        assertThat(table.columns()).anyMatch(c -> c.sensitive() && "mask".equals(c.maskingStrategy()));
    }

    @Test
    void auditTableExcludedFromContext() {
        // Even if repository returns query_audit_logs, whitelist filtering should remove it
        when(repository.findQueryableTableNames()).thenReturn(List.of("customers"));

        SchemaContext context = service.buildContext();

        assertThat(context.tables())
                .map(TableSchema::name)
                .doesNotContain("query_audit_logs");
    }

    @Test
    void sensitiveColumnsMarkedCorrectly() {
        when(repository.findQueryableTableNames()).thenReturn(List.of("customers"));
        when(repository.findColumns("customers")).thenReturn(List.of(
                new ColumnSchema("password", "varchar", false, "password", true, "block"),
                new ColumnSchema("name", "varchar", false, "name", false, null)));

        SchemaContext context = service.buildContext();

        TableSchema table = context.tables().get(0);
        ColumnSchema password = table.columns().stream()
                .filter(c -> c.name().equals("password")).findFirst().orElseThrow();
        assertThat(password.sensitive()).isTrue();
        assertThat(password.maskingStrategy()).isEqualTo("block");
    }

    @Test
    void textSummaryContainsTableAndColumnDescriptions() {
        when(repository.findQueryableTableNames()).thenReturn(List.of("customers"));
        when(repository.findColumns("customers")).thenReturn(List.of(
                new ColumnSchema("id", "bigint", false, "customer primary key", false, null)));

        SchemaContext context = service.buildContext();

        assertThat(context.textSummary())
                .contains("Table: customers")
                .contains("id (bigint)")
                .contains("customer primary key")
                .doesNotContain("password"); // password not in schema in this test
    }

    @Test
    void isQueryableRespectsWhitelist() {
        assertThat(service.isQueryable("customers")).isTrue();
        assertThat(service.isQueryable("query_audit_logs")).isFalse();
        assertThat(service.isQueryable("unknown_table")).isFalse();
    }

    @Test
    void perTableReadFailureDoesNotAbortContext() {
        when(repository.findQueryableTableNames()).thenReturn(List.of("customers", "broken"));
        when(repository.findColumns("customers")).thenReturn(List.of(
                new ColumnSchema("id", "bigint", false, "id", false, null)));
        when(repository.findColumns("broken")).thenThrow(new RuntimeException("db error"));

        SchemaContext context = service.buildContext();

        // customers table is still included; broken table is skipped
        assertThat(context.tables()).map(TableSchema::name).containsExactly("customers");
    }
}
