package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Shared context passed through the validator chain.
 *
 * <p>校验链共享上下文：持有原始 SQL、规范化文本、解析后的 AST 以及相关配置。
 * Parser 只执行一次，后续校验器复用 AST。</p>
 */
public class SqlValidationContext {

    private final String originalSql;
    private final String normalizedSql;
    private final Statement parsedStatement;
    private final JSQLParserException parseException;
    private final boolean statementsSeparated;
    private final GuardrailsProperties properties;

    private SqlValidationContext(Builder builder) {
        this.originalSql = builder.originalSql;
        this.normalizedSql = builder.normalizedSql;
        this.parsedStatement = builder.parsedStatement;
        this.parseException = builder.parseException;
        this.statementsSeparated = builder.statementsSeparated;
        this.properties = builder.properties;
    }

    public String originalSql() {
        return originalSql;
    }

    public String normalizedSql() {
        return normalizedSql;
    }

    /** Parsed AST, or {@code null} when parsing failed or multiple statements were detected. */
    public Statement parsedStatement() {
        return parsedStatement;
    }

    public JSQLParserException parseException() {
        return parseException;
    }

    /** {@code true} when the statement parsed successfully into a single AST. */
    public boolean isParsed() {
        return parsedStatement != null;
    }

    /** {@code true} when the input appears to contain multiple `;`-separated statements. */
    public boolean hasMultipleStatements() {
        return statementsSeparated;
    }

    /** {@code true} when the parsed statement is a SELECT. */
    public boolean isSelectStatement() {
        return parsedStatement instanceof Select;
    }

    public GuardrailsProperties properties() {
        return properties;
    }

    /** Builder that performs parsing once and is then frozen into a context. */
    public static Builder forSql(String sql, GuardrailsProperties properties) {
        return new Builder(sql, properties);
    }

    /** Builder for SqlValidationContext. */
    public static class Builder {
        private final String originalSql;
        private final GuardrailsProperties properties;
        private String normalizedSql;
        private Statement parsedStatement;
        private JSQLParserException parseException;
        private boolean statementsSeparated;

        public Builder(String sql, GuardrailsProperties properties) {
            this.originalSql = sql;
            this.properties = properties;
        }

        /** Provide pre-parsed statement (e.g. from test or external parser). */
        public Builder parsedStatement(Statement statement) {
            this.parsedStatement = statement;
            return this;
        }

        /** Provide parse exception (e.g. from test). */
        public Builder parseException(JSQLParserException exception) {
            this.parseException = exception;
            return this;
        }

        /** Mark that the input contained multiple statements. */
        public Builder statementsSeparated(boolean value) {
            this.statementsSeparated = value;
            return this;
        }

        /** Normalized sql text. */
        public Builder normalizedSql(String sql) {
            this.normalizedSql = sql;
            return this;
        }

        public SqlValidationContext build() {
            if (normalizedSql == null) {
                normalizedSql = originalSql == null ? "" : originalSql.trim();
            }
            if (parsedStatement == null && parseException == null && !statementsSeparated) {
                // 首次解析 SQL；多语句场景下不解析，由多语句校验器处理
                try {
                    parsedStatement = CCJSqlParserUtil.parse(normalizedSql);
                } catch (JSQLParserException ex) {
                    parseException = ex;
                }
            }
            return new SqlValidationContext(this);
        }
    }
}
