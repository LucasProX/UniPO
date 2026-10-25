package com.biecuoguo.permission.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.permission.api.Grant;
import com.biecuoguo.permission.api.Grants;
import com.biecuoguo.permission.api.PermissionApi;
import com.biecuoguo.permission.api.Role;
import com.biecuoguo.permission.api.ScopeType;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 只读的权限查询。只依赖 Mapper，所以任何模块都能注入它而不形成 Bean 循环（同 TaskLookupService 的做法）。
 * 停用、注销的账号一律没有权限，平台管理员也一样。
 */
@Service
public class PermissionService implements PermissionApi {
    private final RoleGrantMapper grantMapper;
    private final UserMapper userMapper;

    public PermissionService(RoleGrantMapper grantMapper, UserMapper userMapper) {
        this.grantMapper = grantMapper;
        this.userMapper = userMapper;
    }

    @Override
    public Grants grantsOf(Long userId) {
        if (userId == null) return Grants.none();
        User user = userMapper.selectById(userId);
        if (user == null || !"active".equals(user.getStatus())) return Grants.none();
        List<Grant> grants = activeRows(userId, AppTime.nowLocal()).stream()
                .map(PermissionService::toGrant)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new Grants("ADMIN".equals(user.getRole()), grants);
    }

    @Override
    public boolean hasScopedGrant(long userId) {
        return !activeRows(userId, AppTime.nowLocal()).isEmpty();
    }

    List<RoleGrant> activeRows(long userId, LocalDateTime now) {
        return grantMapper.selectList(new LambdaQueryWrapper<RoleGrant>()
                .eq(RoleGrant::getUserId, userId)
                .isNull(RoleGrant::getRevokedAt)
                .le(RoleGrant::getStartsAt, now)
                .and(w -> w.isNull(RoleGrant::getExpiresAt).or().gt(RoleGrant::getExpiresAt, now))
                .orderByAsc(RoleGrant::getId));
    }

    /** 库里出现代码不认识的角色名时忽略这一行，宁可少给权限。 */
    static Grant toGrant(RoleGrant row) {
        try {
            return new Grant(row.getId(), Role.valueOf(row.getRole()), ScopeType.valueOf(row.getScopeType()),
                    row.getScopeId(), row.getSchoolId(), row.getExpiresAt());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
