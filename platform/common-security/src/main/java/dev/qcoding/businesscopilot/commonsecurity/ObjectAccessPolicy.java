package dev.qcoding.businesscopilot.commonsecurity;

/** Shared object authorization contract; business modules retain state-machine decisions. */
public interface ObjectAccessPolicy {

    boolean allowed(CurrentActor actor, ObjectAction action, String ownerActorId,
                    String reviewerActorId, boolean reviewQueue);
}
