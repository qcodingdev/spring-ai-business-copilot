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
            // 操作者本人就是业务流程中的人工确认者；高风险队列仍要求显式 token，
            // 不能因为标记为 REVIEW 就让创建者在当前工作台里失去完成闭环的能力。
            return actor.actorId().equals(ownerActorId);
        }
        if (!actor.hasRole(BusinessRole.REVIEWER) || !reviewQueue || action != ObjectAction.REVIEW) {
            return false;
        }
        return reviewerActorId == null || reviewerActorId.isBlank()
                || actor.actorId().equals(reviewerActorId);
    }
}
