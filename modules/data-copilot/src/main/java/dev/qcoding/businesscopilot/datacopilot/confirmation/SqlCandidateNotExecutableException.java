package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

/**
 * Thrown when a SQL candidate is not executable (guardrails failed, wrong token, etc.).
 *
 * <p>SQL 候选不可执行异常。覆盖所有不可执行场景：guardrails 失败、
 * token 不匹配、候选不存在等。</p>
 */
public class SqlCandidateNotExecutableException extends BusinessException {

    public SqlCandidateNotExecutableException() {
        super(ErrorCode.SQL_CANDIDATE_NOT_EXECUTABLE);
    }

    public SqlCandidateNotExecutableException(String ignoredInternalReason) {
        this();
    }
}
