package dev.qcoding.businesscopilot;

import com.zaxxer.hikari.HikariDataSource;
import dev.qcoding.businesscopilot.datacopilot.schema.BusinessDatabaseDialect;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** 创建仅供 Data Copilot 查询使用的独立只读连接。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessQueryDataSourceProperties.class)
@ConditionalOnProperty(prefix = "business-copilot.data-copilot.datasource", name = "enabled", havingValue = "true")
public class BusinessQueryDataSourceConfiguration {

    @Bean(name = "businessQueryDatabaseDialect", defaultCandidate = false)
    BusinessDatabaseDialect businessQueryDatabaseDialect(BusinessQueryDataSourceProperties properties) {
        return BusinessDatabaseDialect.resolve(properties.getDialect(), properties.getUrl());
    }

    @Bean(name = "businessQueryDataSource", defaultCandidate = false)
    DataSource businessQueryDataSource(
            BusinessQueryDataSourceProperties properties,
            @Qualifier("businessQueryDatabaseDialect") BusinessDatabaseDialect dialect) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new IllegalStateException("已启用外部业务查询数据源，但未配置 JDBC URL");
        }
        if (properties.getUsername() == null || properties.getUsername().isBlank()) {
            throw new IllegalStateException("已启用外部业务查询数据源，但未配置用户名");
        }
        String driverClassName = properties.getDriverClassName();
        if (driverClassName == null || driverClassName.isBlank()) {
            driverClassName = dialect.driverClassName();
        }
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(properties.getUrl())
                .username(properties.getUsername())
                .password(properties.getPassword())
                .driverClassName(driverClassName)
                .build();
        dataSource.setPoolName("business-query-pool");
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        dataSource.setConnectionTimeout(properties.getConnectionTimeoutMs());
        dataSource.setConnectionInitSql(dialect.connectionInitSql());
        return dataSource;
    }

    @Bean(name = "businessQueryJdbcTemplate", defaultCandidate = false)
    JdbcTemplate businessQueryJdbcTemplate(
            @Qualifier("businessQueryDataSource") DataSource businessQueryDataSource) {
        return new JdbcTemplate(businessQueryDataSource);
    }
}
