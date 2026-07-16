package dev.qcoding.businesscopilot.commonsecurity;

/** Single-organization ADMIN/OPERATOR/REVIEWER object authorization matrix. */
public class DefaultObjectAccessPolicy implements ObjectAccessPolicy {

    @Override
    public boolean allowed(CurrentActor actor, ObjectAction action, String ownerActorId,
                           String reviewerActorId, boolean reviewQueue) {
        if (actor == null || !actor.authenticated()) {
            return false;
        }
        if (actor.hasRole(BusinessRole.ADMIN)) {
            return true;
        }
        if (actor.hasRole(BusinessRole.OPERATOR)) {
            return action != ObjectAction.REVIEW && actor.actorId().equals(ownerActorId);
        }
        if (!actor.hasRole(BusinessRole.REVIEWER) || !reviewQueue || action != ObjectAction.REVIEW) {
            return false;
        }
        return reviewerActorId == null || reviewerActorId.isBlank()
                || actor.actorId().equals(reviewerActorId);
    }
}
