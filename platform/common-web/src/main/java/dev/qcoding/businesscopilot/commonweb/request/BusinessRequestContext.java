package dev.qcoding.businesscopilot.commonweb.request;

import java.util.Set;

/** 单次请求的链路信息、已认证操作者、业务角色和低基数界面语言。 */
public record BusinessRequestContext(String requestId, String actorId, Set<String> roles, String locale) {

    public BusinessRequestContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        locale = "en-US".equals(locale) ? "en-US" : "zh-CN";
    }

    public BusinessRequestContext(String requestId, String actorId, Set<String> roles) {
        this(requestId, actorId, roles, "zh-CN");
    }

    public BusinessRequestContext(String requestId, String actorId) {
        this(requestId, actorId, Set.of(), "zh-CN");
    }
}
