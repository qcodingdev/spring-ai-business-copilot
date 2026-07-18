package dev.qcoding.businesscopilot.commonsecurity;

/** 解析当前业务请求关联的操作者。 */
@FunctionalInterface
public interface CurrentActorProvider {

    CurrentActor currentActor();
}
