package dev.qcoding.businesscopilot.commonsecurity;

import java.util.Set;

/** Authenticated actor identity and stable business roles for the current request. */
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
