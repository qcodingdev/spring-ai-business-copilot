package dev.qcoding.businesscopilot.commonsecurity;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultObjectAccessPolicyTest {

    private final ObjectAccessPolicy policy = new DefaultObjectAccessPolicy();

    @Test
    void adminCanAccessAnyObject() {
        CurrentActor actor = new CurrentActor("admin", Set.of(BusinessRole.ADMIN));
        assertThat(policy.allowed(actor, ObjectAction.EXECUTE, "someone", null, false)).isTrue();
    }

    @Test
    void operatorCanOnlyAccessOwnNonReviewObjects() {
        CurrentActor actor = new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR));
        assertThat(policy.allowed(actor, ObjectAction.CONFIRM, "operator-1", null, false)).isTrue();
        assertThat(policy.allowed(actor, ObjectAction.CONFIRM, "operator-2", null, false)).isFalse();
        assertThat(policy.allowed(actor, ObjectAction.REVIEW, "operator-1", null, true)).isFalse();
    }

    @Test
    void reviewerRequiresExplicitQueueAndAssignment() {
        CurrentActor actor = new CurrentActor("reviewer-1", Set.of(BusinessRole.REVIEWER));
        assertThat(policy.allowed(actor, ObjectAction.REVIEW, "operator", null, true)).isTrue();
        assertThat(policy.allowed(actor, ObjectAction.REVIEW, "operator", "reviewer-1", true)).isTrue();
        assertThat(policy.allowed(actor, ObjectAction.REVIEW, "operator", "reviewer-2", true)).isFalse();
        assertThat(policy.allowed(actor, ObjectAction.REVIEW, "operator", null, false)).isFalse();
    }
}
