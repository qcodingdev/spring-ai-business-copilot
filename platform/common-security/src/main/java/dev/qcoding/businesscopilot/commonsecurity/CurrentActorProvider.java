package dev.qcoding.businesscopilot.commonsecurity;

/** Resolves the actor associated with the current business request. */
@FunctionalInterface
public interface CurrentActorProvider {

    CurrentActor currentActor();
}
