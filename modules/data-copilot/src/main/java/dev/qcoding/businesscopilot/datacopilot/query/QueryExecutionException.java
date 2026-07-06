package dev.qcoding.businesscopilot.datacopilot.query;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;

/**
 * Thrown when a read-only query execution fails at the database layer.
 *
 * <p>查询执行异常。SQL 执行过程中的数据库异常统一转换成此异常，
 * 不向客户端暴露原始堆栈或连接细节。</p>
 */
public class QueryExecutionException extends BusinessException {

    public QueryExecutionException(String message) {
        super(ErrorCode.QUERY_EXECUTION_ERROR, message);
    }

    public QueryExecutionException(String message, Throwable cause) {
        super(ErrorCode.QUERY_EXECUTION_ERROR, message, cause);
    }
}
