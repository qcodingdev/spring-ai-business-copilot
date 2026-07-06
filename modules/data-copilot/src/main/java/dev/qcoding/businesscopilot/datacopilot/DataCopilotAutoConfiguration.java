package dev.qcoding.businesscopilot.datacopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.datacopilot.confirmation.DataCopilotConfirmationProperties;
import dev.qcoding.businesscopilot.datacopilot.confirmation.InMemorySqlCandidateStore;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateStore;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.explanation.QueryResultSummarizer;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationService;
import dev.qcoding.businesscopilot.datacopilot.query.JdbcReadOnlyQueryExecutor;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionProperties;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionService;
import dev.qcoding.businesscopilot.datacopilot.query.ReadOnlyQueryExecutor;
import dev.qcoding.businesscopilot.datacopilot.schema.DataCopilotSchemaProperties;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SensitiveDataMasker;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for the Data Copilot module.
 *
 * <p>Data Copilot 自动装配。注册 schema 配置、确认机制组件、查询执行组件、结果解释组件。
 * 第一版确认机制使用内存存储，不引入 Redis，不做集群会话一致性。</p>
 */
@Configuration
public class DataCopilotAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.data-copilot.schema")
    public DataCopilotSchemaProperties dataCopilotSchemaProperties() {
        return new DataCopilotSchemaProperties(null, null, null, 0);
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.data-copilot.confirmation")
    public DataCopilotConfirmationProperties dataCopilotConfirmationProperties() {
        return new DataCopilotConfirmationProperties(0);
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.data-copilot.query")
    public QueryExecutionProperties queryExecutionProperties() {
        return new QueryExecutionProperties(0, 0);
    }

    @Bean
    public SqlCandidateStore sqlCandidateStore() {
        // 第一版只用内存存储，不引入 Redis
        return new InMemorySqlCandidateStore();
    }

    @Bean
    public SqlConfirmationService sqlConfirmationService(SqlCandidateStore store,
                                                          DataCopilotConfirmationProperties properties) {
        return new SqlConfirmationService(store, properties);
    }

    @Bean
    public ReadOnlyQueryExecutor readOnlyQueryExecutor(JdbcTemplate jdbcTemplate,
                                                        SqlGuardrailService guardrailService,
                                                        GuardrailsProperties guardrailsProperties,
                                                        SensitiveDataMasker sensitiveDataMasker,
                                                        QueryExecutionProperties queryExecutionProperties) {
        // executor 只负责执行与脱敏，审计由 QueryExecutionService 统一处理
        return new JdbcReadOnlyQueryExecutor(
                jdbcTemplate,
                guardrailService,
                guardrailsProperties,
                sensitiveDataMasker,
                queryExecutionProperties);
    }

    @Bean
    public QueryResultSummarizer queryResultSummarizer() {
        return new QueryResultSummarizer();
    }

    @Bean
    public ResultExplanationService resultExplanationService(AiChatService aiChatService,
                                                              PromptTemplateService promptTemplateService,
                                                              QueryResultSummarizer queryResultSummarizer) {
        return new ResultExplanationService(aiChatService, promptTemplateService, queryResultSummarizer);
    }

    @Bean
    public QueryExecutionService queryExecutionService(SqlConfirmationService confirmationService,
                                                        ReadOnlyQueryExecutor readOnlyQueryExecutor,
                                                        ResultExplanationService resultExplanationService,
                                                        AuditService auditService) {
        return new QueryExecutionService(
                confirmationService,
                readOnlyQueryExecutor,
                resultExplanationService,
                auditService);
    }
}
