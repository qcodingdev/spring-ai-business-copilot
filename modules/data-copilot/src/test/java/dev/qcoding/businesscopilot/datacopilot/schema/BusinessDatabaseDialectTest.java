package dev.qcoding.businesscopilot.datacopilot.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDatabaseDialectTest {

    @Test
    void detectsPostgreSqlAndMySql() {
        assertThat(BusinessDatabaseDialect.resolve(
                "auto", "jdbc:postgresql://localhost:5432/business"))
                .isEqualTo(BusinessDatabaseDialect.POSTGRESQL);
        assertThat(BusinessDatabaseDialect.resolve(
                null, "jdbc:mysql://localhost:3306/business"))
                .isEqualTo(BusinessDatabaseDialect.MYSQL);
    }

    @Test
    void rejectsUnsupportedOrMismatchedTargets() {
        assertThatThrownBy(() -> BusinessDatabaseDialect.resolve(
                "postgresql", "jdbc:mysql://localhost:3306/business"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessDatabaseDialect.resolve(
                "auto", "jdbc:oracle:thin:@localhost:1521/business"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
