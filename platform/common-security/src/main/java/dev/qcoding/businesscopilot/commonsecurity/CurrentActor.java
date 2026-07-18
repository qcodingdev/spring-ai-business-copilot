package dev.qcoding.businesscopilot.commonsecurity;

import java.util.Set;

/** 当前请求中已认证的操作者身份与稳定业务角色。 */
public record CurrentActor(String actorId, Set<BusinessRole> roles) {

    public CurrentActor {
        actorId = actorId == null || actorId.isBlank() ? "anonymous" : actorId;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(BusinessRole role) {
        return roles.contains(role);
    }

    public boolean authenticated() {
        return !"anonymous".equals(actorId);
    }
}
