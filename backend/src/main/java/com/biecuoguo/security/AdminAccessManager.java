package com.biecuoguo.security;

import com.biecuoguo.permission.api.PermissionApi;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * /api/admin/** 的粗粒度门：平台管理员或持有至少一条有效范围授权。
 * 具体能不能动某个对象，由各业务服务用 PermissionApi 按对象的范围判断。
 */
@Component
public class AdminAccessManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final PermissionApi permissions;

    public AdminAccessManager(PermissionApi permissions) {
        this.permissions = permissions;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user) || user.id() == null) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(permissions.grantsOf(user.id()).any());
    }
}
