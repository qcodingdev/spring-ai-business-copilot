package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

/**
 * Thrown when a SQL candidate has expired and can no longer be executed.
 *
 * <p>SQL 候选已过期异常。候选默认 10 分钟有效，过期后不可执行。</p>
 */
public class SqlCandidateExpiredException extends BusinessException {

    public SqlCandidateExpiredException() {
        super(ErrorCode.STATE_CONFLICT);
    }
}
