package dev.qcoding.businesscopilot.commonweb.request;

import java.util.Set;

/** 单次请求的链路信息、已认证操作者与独立于框架的业务角色名称。 */
public record BusinessRequestContext(String requestId, String actorId, Set<String> roles) {

    public BusinessRequestContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public BusinessRequestContext(String requestId, String actorId) {
        this(requestId, actorId, Set.of());
    }
}
