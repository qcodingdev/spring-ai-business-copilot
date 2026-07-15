package dev.qcoding.businesscopilot.datacopilot.confirmation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Service for creating and confirming SQL candidates.
 *
 * <p>SQL 候选确认服务。核心职责：
 * <ul>
 *   <li>为通过 guardrails 的 SQL 候选生成 confirmationToken 并存入服务端存储；</li>
 *   <li>执行阶段仅凭 candidateId + confirmationToken 取出服务端保存的 SQL，不从客户端接收 SQL。</li>
 * </ul>
 *
 * 为什么不能信任客户端传回的 SQL？
 * 客户端可能在确认阶段篡改 SQL 语句（例如绕过 LIMIT、注入 DELETE/UPDATE、
 * 访问非白名单表），从而绕过服务端 guardrails 校验。
 * 因此执行时必须使用服务端存储的原始 SQL，确保执行的是经过安全审查的语句。</p>
 */
public class SqlConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(SqlConfirmationService.class);

    private final SqlCandidateStore store;
    private final DataCopilotConfirmationProperties properties;

    public SqlConfirmationService(SqlCandidateStore store,
                                   DataCopilotConfirmationProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Create an executable candidate with a confirmation token.
     *
     * @param sql the SQL that passed guardrails
     * @return the saved candidate with token and expiry
     */
    public SqlCandidate createExecutableCandidate(String sql) {
        return createExecutableCandidate(sql, null, null, null);
    }

    /**
     * Create an executable candidate with a confirmation token, carrying audit context.
     *
     * @param sql          the SQL that passed guardrails
     * @param requestId    request identifier for audit tracing
     * @param userQuestion original natural-language question for audit
     * @param modelName    AI model name that generated this SQL
     * @return the saved candidate with token, expiry, and audit context
     */
    public SqlCandidate createExecutableCandidate(String sql, String requestId,
                                                   String userQuestion, String modelName) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.candidateTtlMinutes() * 60L);
        SqlCandidate candidate = SqlCandidate.executable(sql, now, expiresAt, requestId, userQuestion, modelName);
        store.save(candidate);
        log.info("Created executable SQL candidate: id={}, expiresAt={}", candidate.candidateId(), expiresAt);
        return candidate;
    }

    /**
     * Create a non-executable candidate (guardrails failed) — no token, no expiry.
     *
     * @param sql the SQL that failed guardrails
     * @return the saved candidate without token
     */
    public SqlCandidate createNotExecutableCandidate(String sql) {
        Instant now = Instant.now();
        SqlCandidate candidate = SqlCandidate.notExecutable(sql, now);
        store.save(candidate);
        log.info("Created non-executable SQL candidate: id={}", candidate.candidateId());
        return candidate;
    }

    /**
     * Atomically confirm and consume a candidate by candidateId + confirmationToken.
     *
     * <p>确认候选并取出 SQL。校验 candidateId、confirmationToken、过期时间、executable。
     * 不能信任客户端 SQL，只能使用服务端存储的原始 SQL。</p>
     *
     * @param candidateId       the candidate identifier
     * @param confirmationToken the confirmation token
     * @return the confirmed SQL candidate, removed from the store before execution
     * @throws SqlCandidateNotExecutableException if candidate not found or token mismatch
     * @throws SqlCandidateExpiredException       if candidate has expired
     */
    public SqlCandidate confirmAndConsume(String candidateId, String confirmationToken) {
        SqlCandidate candidate = store.findById(candidateId);

        // 候选不存在
        if (candidate == null) {
            throw new SqlCandidateNotExecutableException(
                    "SQL candidate not found: " + candidateId);
        }

        // 校验 confirmationToken
        if (candidate.confirmationToken() == null || !candidate.confirmationToken().equals(confirmationToken)) {
            throw new SqlCandidateNotExecutableException(
                    "Invalid confirmation token for candidate: " + candidateId);
        }

        // 校验 executable
        if (!candidate.executable()) {
            throw new SqlCandidateNotExecutableException(
                    "SQL candidate is not executable: " + candidateId);
        }

        // 校验过期
        if (candidate.isExpired()) {
            store.remove(candidateId, candidate);
            throw new SqlCandidateExpiredException(candidateId);
        }

        // 原子消费：并发请求中只有一个能拿到候选，token 不可重放。
        if (!store.remove(candidateId, candidate)) {
            throw new SqlCandidateNotExecutableException(
                    "SQL candidate has already been consumed: " + candidateId);
        }

        log.info("Confirmed and consumed SQL candidate: id={}", candidateId);
        return candidate;
    }

    /** Evict expired candidates from the store. */
    public void evictExpired() {
        store.evictExpired();
    }
}
