package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextCurrentActorProviderTest {

    private final CurrentActorProvider provider = new RequestContextCurrentActorProvider();

    @AfterEach
    void clear() {
        BusinessRequestContextHolder.clear();
    }

    @Test
    void mapsKnownBusinessRoles() {
        BusinessRequestContextHolder.set(new BusinessRequestContext(
                "request-1", "operator-1", Set.of("OPERATOR", "IGNORED")));
        assertThat(provider.currentActor())
                .isEqualTo(new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)));
    }

    @Test
    void missingRequestContextFailsClosedAsAnonymous() {
        assertThat(provider.currentActor())
                .isEqualTo(new CurrentActor("anonymous", Set.of()));
    }
}
