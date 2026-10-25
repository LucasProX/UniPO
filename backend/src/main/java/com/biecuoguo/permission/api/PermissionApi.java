package com.biecuoguo.permission.api;

import com.biecuoguo.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;

/**
 * 所有模块判断「这个人能不能对这个范围做这件事」的唯一入口（ADR-0006）。
 * 授权每次从数据库读，不放进 JWT：撤销后的下一次请求立即生效。
 */
public interface PermissionApi {

    /** 用户此刻的全部权限；userId 为空返回 {@link Grants#none()}。 */
    Grants grantsOf(Long userId);

    /** 是否持有任何未撤销、未过期的范围授权（不含平台管理员）。 */
    boolean hasScopedGrant(long userId);

    default Grants grantsOf(CurrentUser actor) {
        return grantsOf(actor == null ? null : actor.id());
    }

    default boolean can(CurrentUser actor, Capability capability, ScopeTarget target) {
        return grantsOf(actor).can(capability, target);
    }

    default void require(CurrentUser actor, Capability capability, ScopeTarget target) {
        if (!can(actor, capability, target)) throw new AccessDeniedException("没有权限执行该操作");
    }
}
