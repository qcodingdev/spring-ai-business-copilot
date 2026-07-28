package dev.qcoding.businesscopilot.datacopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ObjectAccessPolicy;
import dev.qcoding.businesscopilot.datacopilot.confirmation.DataCopilotConfirmationProperties;
import dev.qcoding.businesscopilot.datacopilot.confirmation.JdbcSqlCandidateStore;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateStore;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.explanation.QueryResultSummarizer;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataGovernanceService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataEnterpriseProperties;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataQueryResultService;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationService;
import dev.qcoding.businesscopilot.datacopilot.query.JdbcReadOnlyQueryExecutor;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionProperties;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionService;
import dev.qcoding.businesscopilot.datacopilot.query.ReadOnlyQueryExecutor;
import dev.qcoding.businesscopilot.datacopilot.schema.DataCopilotSchemaProperties;
import dev.qcoding.businesscopilot.datacopilot.schema.BusinessDatabaseDialect;
import dev.qcoding.businesscopilot.datacopilot.schema.JdbcSchemaMetadataRepository;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaMetadataRepository;
import dev.qcoding.businesscopilot.datacopilot.web.DataCopilotController;
import dev.qcoding.businesscopilot.datacopilot.web.DataEnterpriseController;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SensitiveDataMasker;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Auto-configuration for the Data Copilot module.
 *
 * <p>Data Copilot 自动装配。注册 schema 配置、数据库候选存储、可信确认、
 * 只读查询执行和结果解释组件。候选状态由平台数据库持久化并使用条件更新，
 * 当前不引入 Redis。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "business-copilot.data-copilot", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DataCopilotAutoConfiguration {

    /**
     * Named query boundary. Deployments can override this bean with a dedicated read-only
     * business database; the default keeps the existing single-database demo working.
     */
    @Bean(name = "businessQueryJdbcTemplate")
    @ConditionalOnMissingBean(name = "businessQueryJdbcTemplate")
    public JdbcTemplate businessQueryJdbcTemplate(@Qualifier("jdbcTemplate") JdbcTemplate platformJdbcTemplate) {
        return platformJdbcTemplate;
    }

    @Bean(name = "businessQueryDatabaseDialect")
    @ConditionalOnMissingBean(name = "businessQueryDatabaseDialect")
    public BusinessDatabaseDialect businessQueryDatabaseDialect() {
        return BusinessDatabaseDialect.POSTGRESQL;
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.data-copilot.schema")
    public DataCopilotSchemaProperties dataCopilotSchemaProperties() {
        return new DataCopilotSchemaProperties(null, null, null, 0);
    }

    @Bean
    @Primary
    public GuardrailsProperties dataCopilotGuardrailsProperties(
            @Qualifier("guardrailsProperties") GuardrailsProperties guardrailsProperties,
            DataCopilotSchemaProperties schemaProperties) {
        return new GuardrailsProperties(
                schemaProperties.queryableTables(),
                guardrailsProperties.queryableColumns(),
                guardrailsProperties.blockedColumns(),
                guardrailsProperties.maskedColumns(),
                guardrailsProperties.defaultMaxRows(),
                guardrailsProperties.requireLimit(),
                guardrailsProperties.allowedAggregateFunctions());
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.data-copilot.confirmation")
    public DataCopilotConfirmationProperties dataCopilotConfirmationProperties() {
        return new DataCopilotConfirmationProperties(0);
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.data-copilot.query")
    public QueryExecutionProperties queryExecutionProperties() {
        return new QueryExecutionProperties(0, 0, 0, 0, 0);
    }

    @Bean
    @ConfigurationProperties(prefix = "business-copilot.data-copilot.enterprise")
    public DataEnterpriseProperties dataEnterpriseProperties() {
        return new DataEnterpriseProperties(0, true);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlCandidateStore sqlCandidateStore(
            @Qualifier("jdbcTemplate") JdbcTemplate platformJdbcTemplate) {
        return new JdbcSqlCandidateStore(platformJdbcTemplate);
    }

    @Bean
    public SqlConfirmationService sqlConfirmationService(SqlCandidateStore store,
                                                          DataCopilotConfirmationProperties properties,
                                                          CurrentActorProvider actorProvider,
                                                          ObjectAccessPolicy accessPolicy,
                                                          ConfirmationTokenService tokenService) {
        return new SqlConfirmationService(
                store, properties, actorProvider, accessPolicy, tokenService);
    }

    @Bean
    public SchemaMetadataRepository schemaMetadataRepository(
            @Qualifier("businessQueryJdbcTemplate") JdbcTemplate jdbcTemplate,
            DataCopilotSchemaProperties properties,
            @Qualifier("businessQueryDatabaseDialect") BusinessDatabaseDialect dialect) {
        return new JdbcSchemaMetadataRepository(jdbcTemplate, properties, dialect);
    }

    @Bean
    public SchemaContextService schemaContextService(SchemaMetadataRepository repository,
                                                      DataCopilotSchemaProperties properties) {
        return new SchemaContextService(repository, properties);
    }

    @Bean
    public ReadOnlyQueryExecutor readOnlyQueryExecutor(
                                                        @Qualifier("businessQueryJdbcTemplate") JdbcTemplate jdbcTemplate,
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
    public SqlGenerationService sqlGenerationService(SchemaContextService schemaContextService,
                                                      AiChatService aiChatService,
                                                      PromptTemplateService promptTemplateService,
                                                      SqlGuardrailService guardrailService,
                                                      AuditService auditService,
                                                      GuardrailsProperties guardrailsProperties,
                                                      SqlConfirmationService confirmationService) {
        return new SqlGenerationService(schemaContextService, aiChatService, promptTemplateService,
                guardrailService, auditService, guardrailsProperties, confirmationService);
    }

    @Bean
    public QueryExecutionService queryExecutionService(SqlConfirmationService confirmationService,
                                                        ReadOnlyQueryExecutor readOnlyQueryExecutor,
                                                        ResultExplanationService resultExplanationService,
                                                        AuditService auditService,
                                                        DataQueryResultService dataQueryResultService) {
        return new QueryExecutionService(
                confirmationService,
                readOnlyQueryExecutor,
                resultExplanationService,
                auditService,
                dataQueryResultService);
    }

    @Bean
    public DataQueryResultService dataQueryResultService(
            @Qualifier("jdbcTemplate") JdbcTemplate platformJdbcTemplate,
            ObjectMapper objectMapper,
            CurrentActorProvider actorProvider) {
        return new DataQueryResultService(
                platformJdbcTemplate, objectMapper, actorProvider, Duration.ofHours(24));
    }

    @Bean
    public DataGovernanceService dataGovernanceService(
            @Qualifier("jdbcTemplate") JdbcTemplate platformJdbcTemplate,
            @Qualifier("businessQueryJdbcTemplate") JdbcTemplate businessQueryJdbcTemplate,
            @Qualifier("businessQueryDatabaseDialect") BusinessDatabaseDialect dialect,
            SchemaContextService schemaContextService,
            SqlGuardrailService guardrailService,
            GuardrailsProperties guardrailsProperties,
            SqlConfirmationService confirmationService,
            CurrentActorProvider actorProvider,
            ObjectMapper objectMapper,
            DataEnterpriseProperties enterpriseProperties) {
        return new DataGovernanceService(
                platformJdbcTemplate, businessQueryJdbcTemplate, dialect,
                schemaContextService, guardrailService, guardrailsProperties,
                confirmationService, actorProvider, objectMapper, enterpriseProperties);
    }

    @Bean
    @ConditionalOnMissingBean(DataCopilotController.class)
    public DataCopilotController dataCopilotController(SchemaContextService schemaContextService,
                                                       SqlGenerationService sqlGenerationService,
                                                       QueryExecutionService queryExecutionService,
                                                       AuditService auditService) {
        return new DataCopilotController(schemaContextService, sqlGenerationService,
                queryExecutionService, auditService);
    }

    @Bean
    @ConditionalOnMissingBean(DataEnterpriseController.class)
    public DataEnterpriseController dataEnterpriseController(
            DataGovernanceService governanceService,
            DataQueryResultService resultService,
            QueryExecutionService executionService) {
        return new DataEnterpriseController(governanceService, resultService, executionService);
    }
}
