package dev.qcoding.businesscopilot.commonsecurity;

/** 单组织管理员、操作员和复核员的业务对象授权矩阵。 */
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
            // 普通业务确认可以由对象创建者完成；进入独立复核队列后必须由
            // REVIEWER 或 ADMIN 处理，不能由创建者自己完成四眼复核。
            if (reviewQueue && action == ObjectAction.REVIEW) {
                return false;
            }
            return actor.actorId().equals(ownerActorId);
        }
        if (!actor.hasRole(BusinessRole.REVIEWER) || !reviewQueue || action != ObjectAction.REVIEW) {
            return false;
        }
        return reviewerActorId == null || reviewerActorId.isBlank()
                || actor.actorId().equals(reviewerActorId);
    }
}
