package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** 读取 HTTP 请求上下文过滤器建立的操作者身份与角色。 */
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
                // 框架权限不会自动等同于业务角色。
            }
        }
        return new CurrentActor(context.actorId(), roles);
    }
}
