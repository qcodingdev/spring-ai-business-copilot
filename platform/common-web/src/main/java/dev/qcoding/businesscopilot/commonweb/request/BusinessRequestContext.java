package dev.qcoding.businesscopilot.commonweb.request;

import java.util.Set;

/** Per-request trace, authenticated actor, and framework-independent business role names. */
public record BusinessRequestContext(String requestId, String actorId, Set<String> roles) {

    public BusinessRequestContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public BusinessRequestContext(String requestId, String actorId) {
        this(requestId, actorId, Set.of());
    }
}
