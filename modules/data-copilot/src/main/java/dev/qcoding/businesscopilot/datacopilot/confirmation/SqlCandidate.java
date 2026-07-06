package dev.qcoding.businesscopilot.datacopilot.confirmation;

import java.time.Instant;
import java.util.UUID;

/**
 * A SQL candidate awaiting user confirmation before execution.
 *
 * <p>SQL 候选记录。只有通过 guardrails 校验的候选才会生成 confirmationToken，
 * 前端凭借 candidateId + confirmationToken 申请执行，服务端从存储中取出原始 SQL，
 * 确保执行的是经过安全审查的语句。</p>
 *
 * <p>候选同时携带审计上下文（requestId、userQuestion、modelName），确保执行阶段
 * 写审计时能完整记录查询全生命周期，不丢失原始问题与模型信息。</p>
 *
 * @param candidateId       unique identifier for this candidate
 * @param sql               the SQL statement that passed guardrails
 * @param confirmationToken cryptographically random token that must be presented to execute
 * @param createdAt         when this candidate was created
 * @param expiresAt         when this candidate expires and can no longer be executed
 * @param executable        whether this candidate is allowed to proceed to execution
 * @param requestId         request identifier for audit tracing (null when not tracked)
 * @param userQuestion      original natural-language question for audit (null when not tracked)
 * @param modelName         AI model name that generated this SQL for audit (null when not tracked)
 */
public record SqlCandidate(
        String candidateId,
        String sql,
        String confirmationToken,
        Instant createdAt,
        Instant expiresAt,
        boolean executable,
        String requestId,
        String userQuestion,
        String modelName) {

    /** Create a new executable candidate with a random ID and secure token. */
    public static SqlCandidate executable(String sql, Instant createdAt, Instant expiresAt) {
        return executable(sql, createdAt, expiresAt, null, null, null);
    }

    /** Create a new executable candidate carrying audit context. */
    public static SqlCandidate executable(String sql, Instant createdAt, Instant expiresAt,
                                           String requestId, String userQuestion, String modelName) {
        return new SqlCandidate(
                UUID.randomUUID().toString(),
                sql,
                UUID.randomUUID().toString(),
                createdAt,
                expiresAt,
                true,
                requestId,
                userQuestion,
                modelName);
    }

    /** Create a non-executable candidate (guardrails failed) — no token, no expiry. */
    public static SqlCandidate notExecutable(String sql, Instant createdAt) {
        return new SqlCandidate(
                UUID.randomUUID().toString(),
                sql,
                null,
                createdAt,
                null,
                false,
                null,
                null,
                null);
    }

    /** Check whether this candidate has expired. */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
