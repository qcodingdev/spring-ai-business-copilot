package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Reads actor identity and roles established by the HTTP request context filter. */
public class RequestContextCurrentActorProvider implements CurrentActorProvider {

    @Override
    public CurrentActor currentActor() {
        BusinessRequestContext context = BusinessRequestContextHolder.current();
        if (context == null) {
            return new CurrentActor("anonymous", Set.of());
        }
        EnumSet<BusinessRole> roles = EnumSet.noneOf(BusinessRole.class);
        for (String role : context.roles()) {
            try {
                roles.add(BusinessRole.valueOf(role.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Framework-specific authorities are not automatically business roles.
            }
        }
        return new CurrentActor(context.actorId(), roles);
    }
}
