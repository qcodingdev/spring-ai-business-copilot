package dev.qcoding.businesscopilot.commonsecurity;

/** 共享的对象授权契约；业务状态机决策仍由各业务模块负责。 */
public interface ObjectAccessPolicy {

    boolean allowed(CurrentActor actor, ObjectAction action, String ownerActorId,
                    String reviewerActorId, boolean reviewQueue);
}
