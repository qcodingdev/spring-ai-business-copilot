package dev.qcoding.businesscopilot.datacopilot.query;

import dev.qcoding.businesscopilot.audit.AuditEvent;
import dev.qcoding.businesscopilot.audit.AuditEventType;
import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.audit.AuditStatus;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidate;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateExpiredException;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateNotExecutableException;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationRequest;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationResponse;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationService;
import dev.qcoding.businesscopilot.datacopilot.web.SqlExecutionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the full SQL execution lifecycle with centralized audit.
 *
 * <p>查询执行编排服务。负责：确认候选 → 二次 guardrails（在 executor 内）→ 执行 → 脱敏 → 解释 → 审计。
 * 审计在此统一写入，确保执行阶段能携带完整的 requestId/userQuestion/modelName 上下文。
 * 不在 executor 中写审计，避免丢失请求上下文。</p>
 *
 * <p>审计覆盖全部生命周期场景：
 * <ul>
 *   <li>确认失败（token 无效/过期）→ QUERY_NOT_CONFIRMED</li>
 *   <li>二次 guardrails 失败 → QUERY_FAILURE / VALIDATION_FAILED</li>
 *   <li>执行成功 → QUERY_SUCCESS / EXECUTED（含 rowCount）</li>
 *   <li>执行失败 → QUERY_FAILURE / EXECUTION_FAILED（含错误摘要）</li>
 * </ul></p>
 */
public class QueryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutionService.class);

    private final SqlConfirmationService confirmationService;
    private final ReadOnlyQueryExecutor queryExecutor;
    private final ResultExplanationService explanationService;
    private final AuditService auditService;

    public QueryExecutionService(SqlConfirmationService confirmationService,
                                  ReadOnlyQueryExecutor queryExecutor,
                                  ResultExplanationService explanationService,
                                  AuditService auditService) {
        this.confirmationService = confirmationService;
        this.queryExecutor = queryExecutor;
        this.explanationService = explanationService;
        this.auditService = auditService;
    }

    /**
     * Execute a confirmed SQL candidate and return the result with an AI explanation.
     *
     * <p>执行已确认的 SQL 候选。流程：确认候选 → 执行 → 解释 → 审计。
     * 只传 candidateId + confirmationToken，不信任客户端 SQL。</p>
     *
     * @param candidateId       the candidate identifier
     * @param confirmationToken the confirmation token
     * @return execution response containing the result table and AI explanation
     */
    @Transactional
    public SqlExecutionResponse execute(String candidateId, String confirmationToken) {
        long startMs = System.currentTimeMillis();

        // 1. 确认候选（校验 candidateId + token + 过期 + executable）
        SqlCandidate candidate;
        try {
            candidate = confirmationService.confirmAndConsume(candidateId, confirmationToken);
        } catch (SqlCandidateNotExecutableException | SqlCandidateExpiredException ex) {
            // 确认失败：用户未有效确认，记录 QUERY_NOT_CONFIRMED 审计
            recordNotConfirmedAudit(candidateId, ex.getMessage(), startMs);
            throw ex;
        }

        String requestId = candidate.requestId();
        // The candidate table intentionally does not persist the full user question.
        String userQuestion = "已确认的只读业务查询";
        String sql = candidate.sql();
        String modelName = candidate.modelName();
        var aiMetadata = candidate.aiMetadata();

        // External execution is forbidden unless a durable intent exists in the platform database.
        auditService.recordRequired(new AuditEvent(
                requestId, AuditEventType.QUERY_EXECUTION_INTENT,
                null, sql, sql, AuditStatus.EXECUTION_PENDING,
                null, true, null, null, modelName,
                System.currentTimeMillis() - startMs,
                candidate.ownerActorId(), candidate.actionActorId(),
                aiMetadata != null ? aiMetadata.providerName() : null,
                aiMetadata != null ? aiMetadata.providerRequestId() : null,
                candidate.promptName(), candidate.promptVersion(), candidate.promptHash(),
                candidate.policyVersion(), null,
                aiMetadata != null ? aiMetadata.inputTokens() : null,
                aiMetadata != null ? aiMetadata.outputTokens() : null,
                aiMetadata != null ? aiMetadata.finishReason() : null));

        // 2. 执行 SQL（内部包含二次 guardrails 校验、超时、max rows、脱敏）
        QueryResultTable table;
        try {
            table = queryExecutor.execute(sql);
        } catch (BusinessException ex) {
            // 二次 guardrails 失败或执行失败：区分场景写审计
            if (ex.errorCode() == ErrorCode.SQL_GUARDRAIL_VIOLATION) {
                // 二次 guardrails 失败
                long latencyMs = System.currentTimeMillis() - startMs;
                auditService.record(new AuditEvent(
                        requestId, AuditEventType.QUERY_FAILURE,
                        userQuestion, sql, null,
                        AuditStatus.VALIDATION_FAILED, null, true,
                        null, null, modelName, latencyMs,
                        candidate.ownerActorId(), candidate.actionActorId(),
                        aiMetadata != null ? aiMetadata.providerName() : null,
                        aiMetadata != null ? aiMetadata.providerRequestId() : null,
                        candidate.promptName(), candidate.promptVersion(), candidate.promptHash(),
                        candidate.policyVersion(), "SECONDARY_GUARDRAIL_REJECTED",
                        aiMetadata != null ? aiMetadata.inputTokens() : null,
                        aiMetadata != null ? aiMetadata.outputTokens() : null,
                        aiMetadata != null ? aiMetadata.finishReason() : null));
            } else if (ex.errorCode() == ErrorCode.QUERY_EXECUTION_ERROR) {
                // 执行失败
                long latencyMs = System.currentTimeMillis() - startMs;
                auditService.record(new AuditEvent(
                        requestId, AuditEventType.QUERY_FAILURE,
                        userQuestion, sql, sql,
                        AuditStatus.EXECUTION_FAILED, null, true,
                        null, null, modelName, latencyMs,
                        candidate.ownerActorId(), candidate.actionActorId(),
                        aiMetadata != null ? aiMetadata.providerName() : null,
                        aiMetadata != null ? aiMetadata.providerRequestId() : null,
                        candidate.promptName(), candidate.promptVersion(), candidate.promptHash(),
                        candidate.policyVersion(), null,
                        aiMetadata != null ? aiMetadata.inputTokens() : null,
                        aiMetadata != null ? aiMetadata.outputTokens() : null,
                        aiMetadata != null ? aiMetadata.finishReason() : null));
            }
            throw ex;
        }

        // 3. 执行成功审计（含 rowCount）
        long latencyMs = System.currentTimeMillis() - startMs;
        auditService.record(new AuditEvent(
                requestId, AuditEventType.QUERY_SUCCESS,
                userQuestion, sql, sql,
                AuditStatus.EXECUTED, null, true,
                table.rowCount(), null, modelName, latencyMs,
                candidate.ownerActorId(), candidate.actionActorId(),
                aiMetadata != null ? aiMetadata.providerName() : null,
                aiMetadata != null ? aiMetadata.providerRequestId() : null,
                candidate.promptName(), candidate.promptVersion(), candidate.promptHash(),
                candidate.policyVersion(), null,
                aiMetadata != null ? aiMetadata.inputTokens() : null,
                aiMetadata != null ? aiMetadata.outputTokens() : null,
                aiMetadata != null ? aiMetadata.finishReason() : null));

        // 4. 生成 AI 解释（失败时降级，不影响表格展示）
        ResultExplanationResponse explanation = explanationService.explain(
                new ResultExplanationRequest(userQuestion, sql, table));

        return new SqlExecutionResponse(table, explanation);
    }

    /** Record audit when the user fails to confirm the candidate (cancelled/expired). */
    private void recordNotConfirmedAudit(String candidateId, String errorMessage, long startMs) {
        long latencyMs = System.currentTimeMillis() - startMs;
        auditService.record(new AuditEvent(
                candidateId, AuditEventType.QUERY_NOT_CONFIRMED,
                null, null, null,
                AuditStatus.NOT_CONFIRMED, null, false,
                null, errorMessage, null, latencyMs));
        log.info("已记录未确认查询审计：candidateId={}", candidateId);
    }
}
